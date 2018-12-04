import groovy.json.*

class CheribuildProjectParams implements Serializable {
	boolean skipScm = false
	// Whether to skip the clone/copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipArtifacts = false
	// Whether to skip the copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipInitialSetup = false // skip both the copy artifacts and clone stage
	boolean skipTarball = false // don't create a tarball to archive
	boolean skipArchiving = false // don't archive the artifacts
	String nodeLabel = "linux" // if non-null allocate a new jenkins node using node()
	boolean setGitHubStatus = true

	Object scmOverride = null // Set this to use something other than the default scm variable for checkout
	Object gitInfo = null  // This will be set the the git info from the checkout stage
	Map gitInfoMap = null  // Can be used to pass in a map that will be updated with the git info

	/// general/build parameters
	String target // the cheribuild project name
	String extraArgs = '' // additional arguments to pass to cheribuild.py
	String cpu // --cpu flag for cheribuild
	String sdkCPU  // the SDK used to build (e.g. for cheri256-hybrid will use the cheri256 sdk to build MIPS code)
	String capTableABI = 'pcrel'
	boolean needsFullCheriSDK = true
	String label = 'linux'  // Used when copying artifacts (the label parameter for those other jobs)
	// otherwise pull just a specific set of artifacts
	List artifactsToCopy = [] // List of (job:filter) for artifacts which need copying
	String sdkArchive  // The artifact name filter for the sdk job
	String tarballName  // output tarball name (default is "${target}-${cpu}.tar.xz")
	String customGitCheckoutDir
	// by default we try to do an incremental build and if that fails fall back to a full build
	// FIXME: not sure this is actually working, it seems to rebuild all the time
	boolean noIncrementalBuild = false // whether to force a clean build (i.e. don't pass --no-clean to cheribuild)

	/// Test parameters:
	def testTimeout = 120 * 60 // timeout for running tests (default 2 hours)
	boolean minimalTestImage
	String testScript  // if set this will be invoked by ./boot_cheribsd.py in the test stage. If not tests are skipped
	String testExtraArgs = ''  // Additional command line options to be passed to ./boot_cheribsd.py
	boolean runTestsInDocker = false // Seems to be really slow (1 min 44 until init instead of 15 secs)
	// FIXME: implement this:
	// List testOutputs  // if set these files will be scp'd from CheriBSD after running the tests (e.g. JUnit XML files)

	String buildStage = null // Label for the build stage

	/// hooks
	def beforeSCM // callback before checking out the sources
	def beforeBuild  // callback before starting docker
	def beforeBuildInDocker  // first command inside docker
	def beforeTarball  // after building but before creating the tarball
	def afterBuildInDocker  // after building and tarball (no longer inside docker)
	def afterBuild  // after building and tarball (no longer inside docker)
	def beforeTests // before running the tests (before docker)
	def beforeTestsInDocker // before running the tests (inside docker)
	// def afterTestsInCheriBSD // before running the tests (sent to cheribsd command line)
	def afterTestsInDocker // before running the tests (inside docker, cheribsd no longer running)
	def afterTests // before running the tests (no longer inside docker)
}

// FIXME: all this jenkins transforming stuff is ugly... how can I access the jenkins globals?

// Run a beforeXXX hook (beforeBuild, beforeTarball, etc.)
def runCallback(CheribuildProjectParams proj, cb) {
	// def cb = this."${hook}"
	if (!cb) {
		return
	}
	// echo "Running callback ${hook}"
	if ((cb instanceof Closure) || cb.metaClass.respondsTo(cb, 'call')) {
		//noinspection GroovyAssignabilityCheck
		cb(proj.cpu)
	} else {
		assert cb instanceof String
		if (!cb.allWhitespace) {
			sh cb
		}
	}
}

def build(CheribuildProjectParams proj) {
	runCallback(proj, proj.beforeBuild)
	// No docker yet
	// sdkImage.inside('-u 0') {
	ansiColor('xterm') {
		sh "rm -fv ${proj.tarballName}; pwd"
		runCallback(proj, proj.beforeBuildInDocker)
		def cheribuildArgs = "${proj.target} --cpu ${proj.cpu} ${proj.extraArgs} --cap-table-abi=${proj.capTableABI}"
		def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${cheribuildArgs}"
		// by default try an incremental build first and if that fails fall back to a clean build
		// this behaviour can be disabled by passing noIncrementalBuild: true
		if (proj.noIncrementalBuild) {
			sh "${cheribuildCmd}"
		} else {
			sh "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
		}
		if (!proj.skipTarball) {
			runCallback(proj, proj.beforeTarball)
			sh "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${proj.tarballName} --no-build ${cheribuildArgs}"
			sh 'ls -lah; ls -lah tarball || true'
		}
		runCallback(proj, proj.afterBuildInDocker)
	}
	// }
	if (!proj.skipTarball && !proj.skipArchiving) {
		archiveArtifacts allowEmptyArchive: false, artifacts: proj.tarballName, fingerprint: true, onlyIfSuccessful: true
	}
	runCallback(proj, proj.afterBuild)
}

def runTestsImpl(CheribuildProjectParams proj, String testImageArgs, String qemuPath) {
	def testCommand = "'export CPU=${proj.cpu}; " + proj.testScript.replaceAll('\'', '\\\'') + "'"
	echo "Test command = ${testCommand}"
	ansiColor('xterm') {
		runCallback(proj, proj.beforeTestsInDocker)
		sh "\$WORKSPACE/cheribuild/test-scripts/boot_cheribsd.py --qemu-cmd ${qemuPath} ${testImageArgs} --test-command ${testCommand} --test-archive ${proj.tarballName} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}"
		runCallback(proj, proj.afterTestsInDocker)
	}
}

def runTests(CheribuildProjectParams proj) {
	if (proj.cpu != "mips" && proj.cpu != "cheri128" && proj.cpu != "cheri256")
		error("Running tests for target ${proj.cpu} not supported yet")

	String baseABI = 'n64'
	String imagePrefix = "ERROR"
	String kernelPrefix = "ERROR"
	String qemuCommand = "ERROR"
	if (proj.cpu == 'mips') {
		kernelPrefix = 'freebsd'
		imagePrefix = 'freebsd'
		qemuCommand = "qemu-system-cheri256"
	} else {
		CPU_PARAM = proj.cpu
		kernelPrefix = proj.cpu == 'cheri256' ? 'cheribsd-cheri' : 'cheribsd128-cheri128'
		imagePrefix = proj.cpu == 'cheri256' ? 'cheribsd' : 'cheribsd128'
		qemuCommand = "qemu-system-${proj.cpu}"
	}

	stage("Copy ${proj.cpu} CheriBSD image") {
		// boot a world with a hybrid userspace (it contains all the necessary shared libs)
		// There is no need for the binaries to be CHERIABI
		diskImageProjectName = "CheriBSD-allkernels-multi/BASE_ABI=${baseABI},CPU=${proj.cpu},ISA=vanilla,label=freebsd"
		sh 'rm -rfv $WORKSPACE/cheribsd-full.* $WORKSPACE/cheribsd-minimal.* $WORKSPACE/cheribsd-malta64-kernel*'
		copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${imagePrefix}-full.img.xz", target: '.', fingerprintArtifacts: false
		copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${imagePrefix}-jenkins_bluehive.img.xz", target: '.', fingerprintArtifacts: false
		copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${kernelPrefix}-malta64-kernel.bz2", target: '.', fingerprintArtifacts: false
		sh """
mv -f ctsrd/cheribsd/trunk/bsdtools/${imagePrefix}-full.img.xz \$WORKSPACE/cheribsd-full.img.xz
mv -f ctsrd/cheribsd/trunk/bsdtools/${imagePrefix}-jenkins_bluehive.img.xz \$WORKSPACE/cheribsd-minimal.img.xz
mv -f ctsrd/cheribsd/trunk/bsdtools/${kernelPrefix}-malta64-kernel.bz2 \$WORKSPACE/cheribsd-malta64-kernel.bz2
ls -la \$WORKSPACE
"""

	}
	def testImageArgs = ''
	if (proj.minimalTestImage) {
		testImageArgs = "--disk-image \$WORKSPACE/cheribsd-minimal.img.xz"
	} else {
		testImageArgs = "--disk-image \$WORKSPACE/cheribsd-full.img.xz"
	}
	testImageArgs += " --kernel \$WORKSPACE/cheribsd-malta64-kernel.bz2 --no-keep-compressed-images"
	runCallback(proj, proj.beforeTests)
	if (proj.runTestsInDocker) {
		// Try to speed it up by extracting to tmpfs
		testImageArgs += " --extract-images-to /images"
		def cheribsdImage = docker.image("ctsrd/qemu-cheri:latest")
		cheribsdImage.pull()
		// Currently the full image is 5.59G
		cheribsdImage.inside('-u 0 --rm --tmpfs /images:rw,noexec,nosuid,size=7g') {
			sh 'df -h /images'
			runTestsImpl(proj, testImageArgs, "/usr/local/bin/${qemuCommand}")
		}
	} else {
		// copy qemu archive and run directly on the host
		dir ('qemu-linux') { deleteDir() }
		copyArtifacts projectName: "qemu/qemu-cheri", filter: "qemu-linux/**", target: '.', fingerprintArtifacts: false
		// We may need to create a dummy ssh key and use a unique port number:
		int sshPort = 12345 + Integer.parseInt(env.EXECUTOR_NUMBER)
		sh 'test -e $WORKSPACE/id_ed25519 || ssh-keygen -t ed25519 -N \'\' -f $WORKSPACE/id_ed25519 < /dev/null'
		testImageArgs += " --ssh-key \$WORKSPACE/id_ed25519.pub --ssh-port ${sshPort}"
		runTestsImpl(proj, testImageArgs, "\$WORKSPACE/qemu-linux/bin/${qemuCommand}")

	}
	runCallback(proj, proj.afterTests)
}

def fileOutsideWorkspaceExists(String path) {
	def returncode = sh returnStatus: true, script: "stat ${path}"
	return returncode == 0
}

def runCheribuildImpl(CheribuildProjectParams proj) {
	if (!proj.tarballName) {
		proj.tarballName = "${proj.target}-${proj.cpu}.tar.xz"
	}

	if (!proj.cpu) {
		error("cpu parameter was not set!")
	}
	// compute sdkCPU from args
	if (!proj.sdkCPU) {
		proj.sdkCPU = proj.cpu
		if (proj.sdkCPU.startsWith("hybrid-")) {
			proj.sdkCPU = proj.sdkCPU.substring("hybrid-".length())
		}
		// Build using the MIPS SDK for native projects
		if (proj.cpu == 'native' || proj.cpu == 'x86') {
			proj.sdkCPU = 'mips'
		}
	}
	env.CPU = proj.cpu
	env.SDK_CPU = proj.sdkCPU

	def gitHubCommitSHA = null
	def gitHubRepoURL = null

	if (proj.skipInitialSetup) {
		proj.skipScm = true
		proj.skipArtifacts = true
	}
	stage("Checkout") {
		if (!proj.skipScm) {
			echo "Target CPU: ${proj.cpu}, SDK CPU: ${proj.sdkCPU}, output: ${proj.tarballName}"
			// def sdkImage = docker.image("ctsrd/cheri-sdk-${proj.sdkCPU}:latest")
			// sdkImage.pull() // make sure we have the latest available from Docker Hub
			runCallback(proj, proj.beforeSCM)

			dir(proj.customGitCheckoutDir ? proj.customGitCheckoutDir : proj.target) {
				def realScm = proj.scmOverride != null ? proj.scmOverride : scm
				proj.gitInfo = checkout realScm
				echo("Checkout result: ${proj.gitInfo}")
				gitHubCommitSHA = proj.gitInfo?.GIT_COMMIT
				gitHubRepoURL = proj.gitInfo?.GIT_URL
				if (proj.gitInfoMap != null)
					proj.gitInfoMap << proj.gitInfo  // store it so it exists even on exception
			}
		}
		dir('cheribuild') {
			def cheribuildSCM = [
				$class: 'GitSCM',
				branches: [[name: '*/master']],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
				userRemoteConfigs: [[url: 'https://github.com/CTSRD-CHERI/cheribuild.git']]
			]
			if (fileOutsideWorkspaceExists('/var/tmp/git-reference-repos/cheribuild')) {
				cheribuildSCM["extensions"] = [
					[$class: 'CloneOption', depth: 0, noTags: true, reference: '/var/tmp/git-reference-repos/cheribuild', shallow: false, timeout: 5]
				]
				echo("Using reference repo for cheribuild")
			}
			def x = checkout changelog: false, poll: false, scm: cheribuildSCM
			// def x = git 'https://github.com/CTSRD-CHERI/cheribuild.git', changelog: false, poll: false
			echo("Checked out cheribuild: ${x}")
		}
	}
	if (!proj.skipArtifacts) {
		stage("Setup SDK for ${proj.target} (${proj.cpu})") {
			for (artifacts in proj.artifactsToCopy) {
				copyArtifacts projectName: artifacts.job, filter: artifacts.filter, fingerprintArtifacts: false
			}
			// now copy all the artifacts
			if (proj.needsFullCheriSDK) {
				fetchCheriSDK(target: proj.target, cpu: proj.cpu, compilerOnly: false, buildOS: proj.label, capTableABI: proj.capTableABI, extraCheribuildArgs: proj.extraArgs)
			}
			echo 'WORKSPACE after checkout:'
			sh 'ls -la'
		}
	}
	def buildStage = proj.buildStage ? proj.buildStage : "Build ${proj.target} for ${proj.cpu}"
	stage(buildStage) {
		build(proj)
	}
	if (proj.testScript) {
		stage("run tests for ${proj.cpu}") {
			runTests(proj)
		}
	}
	warnings canComputeNew: false, canResolveRelativePaths: false, consoleParsers: [[parserName: 'Clang (LLVM based)']]
	step([$class: 'AnalysisPublisher', canComputeNew: false])
	if (proj.setGitHubStatus) {
		def message = "${currentBuild.description} ${proj.cpu}"
		def githubCommitStatusContext = "ci/jenkins/build-status/${env.JOB_NAME}/${proj.cpu}"
		if (proj.nodeLabel) {
			message += " ${proj.nodeLabel}"
			githubCommitStatusContext += "/${proj.nodeLabel}"
		}
		setGitHubStatus(proj.gitInfo + [message: message, context: githubCommitStatusContext])
	}
	// TODO: clean up properly and remove the created artifacts?
}

def runCheribuild(Map args) {
	def params = new CheribuildProjectParams()
	for (it in args) {
		try {
			params[it.key] = it.value
		} catch (MissingPropertyException e) {
			error("cheribuildProject: Unknown argument ${it.key}: ${e}")
			return params
		} catch (IllegalArgumentException e) {
			error("cheribuildProject: Bad value ${it.value} for argument ${it.key}: ${e.getMessage()}")
			return params
		} catch (e) {
			error("cheribuildProject: Could not set argument ${it.key} to ${it.value}: ${e}")
			return params
		}
	}
	try {
		properties([
				pipelineTriggers([
						[$class: "GitHubPushTrigger"]
				]),
				disableConcurrentBuilds(),
				[$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
		])
	} catch(e) {
		echo("FAILED TO SET GitHub push trigger in Jenkinsfile: ${e}")
	}
	// The map spread operator is not supported in Jenkins
	// def project = new CheribuildProjectParams(target: args.name, *:args)
	if (params.nodeLabel != null) {
		node(params.nodeLabel) {
			runCheribuildImpl(params)
		}
	} else {
		runCheribuildImpl(params)
	}
	return params
}

// This is what gets called from jenkins
def call(Map args) {
	// just call the real method here so that I can run the tests
	// the problem is that if I invoke call I get endless recursion
	try {
		return runCheribuild(args)
	} catch (e) {
		currentBuild.result = 'FAILURE'
		throw e
	}
}
