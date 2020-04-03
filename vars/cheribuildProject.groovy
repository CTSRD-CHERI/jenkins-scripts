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
	String uniqueId = null // Used for the github/analysis ID
	String buildOS = null // Used when copying artifacts (the label parameter for those other jobs)
	boolean setGitHubStatus = true
	String gitHubStatusContext = null

	Object scmOverride = null // Set this to use something other than the default scm variable for checkout
	Object gitInfo = [:]  // This will be set the the git info from the checkout stage
	Map gitInfoMap = null  // Can be used to pass in a map that will be updated with the git info

	/// general/build parameters
	String target // the cheribuild project name
	String extraArgs = '' // additional arguments to pass to cheribuild.py
	String cpu // --cpu flag for cheribuild
	String sdkCPU  // the SDK used to build (e.g. for cheri256-hybrid will use the cheri256 sdk to build MIPS code)
	String capTableABI = 'pcrel'
	boolean needsFullCheriSDK = true
	boolean sdkCompilerOnly = false
	// otherwise pull just a specific set of artifacts
	List artifactsToCopy = [] // List of (job:filter) for artifacts which need copying
	String sdkArchive  // The artifact name filter for the sdk job
	String tarballName  // output tarball name (default is "${target}-${cpu}.tar.xz")
	String customGitCheckoutDir
	// by default we try to do an incremental build and if that fails fall back to a full build
	// FIXME: not sure this is actually working, it seems to rebuild all the time
	boolean incrementalBuild = false // whether to force a clean build (i.e. don't pass --no-clean to cheribuild)

	/// Test parameters:
	def testTimeout = 120 * 60 // timeout for running tests (default 2 hours)
	boolean minimalTestImage = true
	boolean runTests = false
	boolean useCheriKernelForMipsTests = false
	String testScript  // if set this will be invoked by ./boot_cheribsd.py in the test stage. If not tests are skipped
	String testExtraArgs = ''  // Additional command line options to be passed to ./boot_cheribsd.py
	boolean runTestsInDocker = false // Seems to be really slow (1 min 44 until init instead of 15 secs)
	// FIXME: implement this:
	// List testOutputs  // if set these files will be scp'd from CheriBSD after running the tests (e.g. JUnit XML files)

	String buildStage = null // Label for the build stage
	String stageSuffix = null // Suffix for the build/test stage
	static Map uniqueIDs = [:]

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
		echo("Callback type: " + cb.getClass().getCanonicalName())
		def callbackString = "${cb}"
		if (!callbackString.allWhitespace) {
			sh label: 'callback', script: cb
		}
	}
}

def build(CheribuildProjectParams proj, String stageSuffix) {
	runCallback(proj, proj.beforeBuild)
	// No docker yet
	// sdkImage.inside('-u 0') {
	ansiColor('xterm') {
		sh "rm -fv ${proj.tarballName}; pwd"
		runCallback(proj, proj.beforeBuildInDocker)
		def cheribuildArgs = "${proj.target} --cpu ${proj.cpu} ${proj.extraArgs} --cap-table-abi=${proj.capTableABI}"
		def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${cheribuildArgs}"
		// By default do a full rebuild. This can be disabled by passing incrementalBuild: true
		if (proj.incrementalBuild) {
			sh label: "Building with cheribuild (incremental) ${stageSuffix}", script: "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
		} else {
			sh label: "Building with cheribuild ${stageSuffix}", script:  "${cheribuildCmd}"
		}
		if (!proj.skipTarball) {
			runCallback(proj, proj.beforeTarball)
			sh label: "Create tarball ${stageSuffix}", script: "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${proj.tarballName} --no-build ${cheribuildArgs}"
			sh label: "List tarball  ${stageSuffix}", script: 'ls -lah; ls -lah tarball || true'
		}
		runCallback(proj, proj.afterBuildInDocker)
	}
	// }
	if (!proj.skipTarball && !proj.skipArchiving) {
		archiveArtifacts allowEmptyArchive: false, artifacts: proj.tarballName, fingerprint: true, onlyIfSuccessful: true
	}
	runCallback(proj, proj.afterBuild)
}

def runTestsImpl(CheribuildProjectParams proj, String testImageArgs, String qemuPath, String testSuffix) {
	ansiColor('xterm') {
		runCallback(proj, proj.beforeTestsInDocker)
		if (proj.testScript) {
			def testCommand = "'export CPU=${proj.cpu}; " + proj.testScript.replaceAll('\'', '\\\'') + "'"
			echo "Test command = ${testCommand}"
			sh label: "Running simple test (${testSuffix})", script: "\$WORKSPACE/cheribuild/test-scripts/run_simple_tests.py --qemu-cmd ${qemuPath} ${testImageArgs} --test-command ${testCommand} --test-archive ${proj.tarballName} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}"
		} else {
			sh label: "Running tests with cheribuild ${testSuffix}", script: "\$WORKSPACE/cheribuild/jenkins-cheri-build.py --cpu=${proj.cpu} --test ${proj.target} ${proj.extraArgs} --test-extra-args=\"--qemu-cmd ${qemuPath} ${testImageArgs} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}\""
		}
		runCallback(proj, proj.afterTestsInDocker)
	}
}

def runTests(CheribuildProjectParams proj, String testSuffix) {
	// Custom test script only support for CheriBSD
	if (proj.testScript)
		if (proj.cpu != "mips" && proj.cpu != "cheri128" && proj.cpu != "cheri256")
			error("Running tests for target ${proj.cpu} not supported yet")

	String baseABI = 'n64'
	String imagePrefix = "ERROR"
	String kernelPrefix = "ERROR"
	String qemuCommand = "ERROR"
	String test_cpu = proj.cpu
	if (test_cpu == 'mips' && proj.useCheriKernelForMipsTests) {
		test_cpu = 'cheri128'
	}
	if (test_cpu == 'mips') {
		kernelPrefix = 'freebsd'
		imagePrefix = 'freebsd'
		qemuCommand = "qemu-system-cheri256"
	} else {
		kernelPrefix = test_cpu == 'cheri256' ? 'cheribsd-cheri' : 'cheribsd128-cheri128'
		imagePrefix = test_cpu == 'cheri256' ? 'cheribsd' : 'cheribsd128'
		qemuCommand = "qemu-system-${test_cpu}"
	}

	def testImageArgs = ''
	if (test_cpu != "native") {
		// boot a world with a hybrid userspace (it contains all the necessary shared libs)
		// There is no need for the binaries to be CHERIABI
		diskImageProjectName = "CheriBSD-allkernels-multi/BASE_ABI=${baseABI},CPU=${test_cpu},ISA=vanilla,label=freebsd"
		sh 'rm -rfv $WORKSPACE/cheribsd-full.* $WORKSPACE/cheribsd-minimal.* $WORKSPACE/cheribsd-malta64-kernel*'
		if (proj.minimalTestImage) {
			copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${kernelPrefix}-malta64-mfs-root-minimal-cheribuild-kernel.bz2", target: '.', fingerprintArtifacts: false, flatten: true, selector: lastSuccessful()
			sh "ln -sfn \$WORKSPACE/${kernelPrefix}-malta64-mfs-root-minimal-cheribuild-kernel.bz2 \$WORKSPACE/${test_cpu}-malta64-minimal-kernel.bz2"
			testImageArgs = "--kernel ${test_cpu}-malta64-minimal-kernel.bz2"
		} else {
			copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${imagePrefix}-full.img.xz", target: '.', fingerprintArtifacts: false, flatten: true, selector: lastSuccessful()
			copyArtifacts projectName: diskImageProjectName, filter: "ctsrd/cheribsd/trunk/bsdtools/${kernelPrefix}-malta64-kernel.bz2", target: '.', fingerprintArtifacts: false, flatten: true, selector: lastSuccessful()
			sh """
ln -sfn \$WORKSPACE/${imagePrefix}-full.img.xz \$WORKSPACE/${test_cpu}-full.img.xz
ln -sfn \$WORKSPACE/${kernelPrefix}-malta64-kernel.bz2 \$WORKSPACE/${test_cpu}-malta64-kernel.bz2
"""
			testImageArgs = "--disk-image \$WORKSPACE/${test_cpu}-full.img.xz"
			testImageArgs += " --kernel \$WORKSPACE/${test_cpu}-malta64-kernel.bz2 --no-keep-compressed-images"
		}
		sh "ls -la \$WORKSPACE"
	}

	runCallback(proj, proj.beforeTests)
	if (proj.runTestsInDocker) {
		// Try to speed it up by extracting to tmpfs
		testImageArgs += " --extract-images-to /images"
		def cheribsdImage = docker.image("ctsrd/qemu-cheri:latest")
		cheribsdImage.pull()
		// Currently the full image is 5.59G
		cheribsdImage.inside('-u 0 --rm --tmpfs /images:rw,noexec,nosuid,size=7g') {
			sh 'df -h /images'
			runTestsImpl(proj, testImageArgs, "/usr/local/bin/${qemuCommand}", testSuffix)
		}
	} else {
		if (test_cpu != "native") {
			// copy qemu archive and run directly on the host
			dir("qemu-${proj.buildOS}") { deleteDir() }
			copyArtifacts projectName: "qemu/qemu-cheri", filter: "qemu-${proj.buildOS}/**", target: '.', fingerprintArtifacts: false
			sh label: 'generate SSH key', script: 'test -e $WORKSPACE/id_ed25519 || ssh-keygen -t ed25519 -N \'\' -f $WORKSPACE/id_ed25519 < /dev/null'
			testImageArgs += " --ssh-key \$WORKSPACE/id_ed25519.pub"
		}
		runTestsImpl(proj, testImageArgs, "\$WORKSPACE/qemu-${proj.buildOS}/bin/${qemuCommand}", testSuffix)

	}
	runCallback(proj, proj.afterTests)
}

def runCheribuildImpl(CheribuildProjectParams proj) {
	if (!proj.tarballName) {
		proj.tarballName = "${proj.target}-${proj.cpu}.tar.xz"
	}
	if (!proj.cpu) {
		proj.cpu = "default"
	}
	if (!proj.buildOS) {
		proj.buildOS = inferBuildOS()
		echo("Inferred build OS: '${proj.buildOS}'")
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
	// Note: env.FOO = ... sets the environment globally across all nodes
	// so it cannot be used if we want to support cheribuildProject inside
	// parallel blocks
	// env.CPU = proj.cpu
	// env.SDK_CPU = proj.sdkCPU

	// echo("env before =${env}")
	withEnv(["CPU=${proj.cpu}", "SDK_CPU=${proj.sdkCPU}"]) {
		// echo("env in block=${env}")
		if (!proj.uniqueId) {
			proj.uniqueId = "${env.JOB_NAME}/${proj.target}/${proj.cpu}"
			if (proj.nodeLabel)
				proj.uniqueId += "/${proj.nodeLabel}"
			while (CheribuildProjectParams.uniqueIDs.containsKey(proj.uniqueId.toString())) {
				proj.uniqueId += "_1"
			}
			CheribuildProjectParams.uniqueIDs.put(proj.uniqueId, proj.uniqueId)
		}
		if (!proj.gitHubStatusContext) {
			proj.gitHubStatusContext = "jenkins/status/${proj.uniqueId}"
		}
		try {
			runCheribuildImplWithEnv(proj)
		} catch (e) {
			echo("Marking current build as failed!")
			currentBuild.result = 'FAILURE'
			throw e
		} finally {
			if (env.CHANGE_ID) {
				echo("Setting PR status")
				pullRequest.createStatus(status: currentBuild.currentResult,
						context: proj.gitHubStatusContext,
						description: "${proj.stageSuffix}: Done.",
						targetUrl: "${env.JOB_URL}/testResults")
			} else if (proj.setGitHubStatus) {
				def message = "${currentBuild.projectName}"
				if (proj.nodeLabel) {
					message += " ${proj.nodeLabel}"
				}
				echo("Setting github status after build")
				if (currentBuild.result == null)
					currentBuild.result = currentBuild.currentResult
				// Avoid setting an error flag on github commits just because binutils is still broken
				echo("final result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
				setGitHubStatus(proj.gitInfo + [message: message, context: proj.gitHubStatusContext])
			}
		}
	}
}

def runCheribuildImplWithEnv(CheribuildProjectParams proj) {
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
			def x = cloneGitRepoWithReference(url: "https://github.com/CTSRD-CHERI/cheribuild.git", changelog: false, poll: false, refdir: "cheribuild")
			echo("Checked out cheribuild: ${x}")
		}
	}
	if(env.CHANGE_ID) {
		params.extraArgs += " --pretend"
		pullRequest.createStatus(status: 'pending',
				context: proj.gitHubStatusContext,
				description: "About to build PR#${pullRequest.id}...",
				targetUrl: "${env.JOB_URL}/testResults")
	} else if (proj.setGitHubStatus) {
		setGitHubStatus(proj.gitInfo + [message: "${currentBuild.projectName} building ...", context: proj.gitHubStatusContext])
	}
	if (!proj.skipArtifacts) {
		stage("Setup SDK for ${proj.target} (${proj.cpu})") {
			// Can't use a for loop here: https://issues.jenkins-ci.org/browse/JENKINS-49732
			proj.artifactsToCopy.each { artifacts ->
				copyArtifacts projectName: artifacts.job, filter: artifacts.filter, fingerprintArtifacts: false
			}
			// now copy all the artifacts
			if (proj.needsFullCheriSDK) {
				fetchCheriSDK(target: proj.target, cpu: proj.cpu, compilerOnly: proj.sdkCompilerOnly, buildOS: proj.buildOS, capTableABI: proj.capTableABI, extraCheribuildArgs: proj.extraArgs)
			}
			echo 'WORKSPACE after checkout:'
			sh 'ls -la'
		}
	}
	def buildSuffix = proj.stageSuffix ? proj.stageSuffix : " for ${proj.cpu}"
	def buildStage = proj.buildStage ? proj.buildStage : "Build ${proj.target} ${buildSuffix}"
	stage(buildStage) {
		build(proj, buildSuffix)
	}
	if (proj.testScript || proj.runTests) {
		def testSuffix = proj.stageSuffix ? proj.stageSuffix : " for ${proj.cpu}"
		stage("Run ${proj.target} tests ${testSuffix}") {
			runTests(proj, testSuffix)
		}
	}
	def analysisId = proj.stageSuffix ? proj.stageSuffix : "${proj.uniqueId}"
	analysisId.replace(' ', '_')
	recordIssues aggregatingResults: true, blameDisabled: true, enabledForFailure: true, tools: [clang(id: analysisId)]
	//warnings canComputeNew: false, canResolveRelativePaths: false, consoleParsers: [[parserName: 'Clang (LLVM based)']]
	//step([$class: 'AnalysisPublisher', canComputeNew: false])
	// TODO: clean up properly and remove the created artifacts?
}

def runCheribuild(Map args) {
	def params = new CheribuildProjectParams()
	// Can't use a for loop here: https://issues.jenkins-ci.org/browse/JENKINS-49732
	args.each { it ->
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
						githubPush()
				]),
				disableConcurrentBuilds(),
				disableResume(),
				copyArtifactPermission('*'), // New in copyartifacts version 1.41
				// [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
		])
	} catch(e) {
		echo("FAILED TO SET GitHub push trigger in Jenkinsfile: ${e}")
	}
	try {
		// Github
		properties([
				pipelineTriggers([
						issueCommentTrigger('.*test this please.*')
				])
		])
	}  catch(e) {
		echo("FAILED TO SET GitHub issue trigger in Jenkinsfile: ${e}")
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
		echo("Marking current build as failed!")
		currentBuild.result = 'FAILURE'
		throw e
	}
}
