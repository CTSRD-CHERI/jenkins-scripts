import groovy.json.*

class CheribuildProjectParams implements Serializable {
	boolean skipScm = false
	// Whether to skip the clone/copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipArtifacts = false
	// Whether to skip the copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipInitialSetup = false // skip both the copy artifacts and clone stage
	String nodeLabel = "linux" // if non-null allocate a new jenkins node using node()
	boolean setGitHubStatus = true


	/// general/build parameters
	String target // the cheribuild project name
	String extraArgs = '' // additional arguments to pass to cheribuild.py
	String cpu // --cpu flag for cheribuild
	String sdkCPU  // the SDK used to build (e.g. for cheri256-hybrid will use the cheri256 sdk to build MIPS code)
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
	// FIXME: implement this:
	// List testOutputs  // if set these files will be scp'd from CheriBSD after running the tests (e.g. JUnit XML files)

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
		def cheribuildArgs = "${proj.target} --cpu ${proj.cpu} ${proj.extraArgs}"
		def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${cheribuildArgs}"
		// by default try an incremental build first and if that fails fall back to a clean build
		// this behaviour can be disabled by passing noIncrementalBuild: true
		if (proj.noIncrementalBuild) {
			sh "${cheribuildCmd}"
		} else {
			sh "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
		}
		runCallback(proj, proj.beforeTarball)
		sh "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${proj.tarballName} --no-build ${cheribuildArgs}"
		runCallback(proj, proj.afterBuildInDocker)
	}
	// }
	sh 'ls -lah; ls -lah tarball || true'
	archiveArtifacts allowEmptyArchive: false, artifacts: proj.tarballName, fingerprint: true, onlyIfSuccessful: true
	runCallback(proj, proj.afterBuild)
}

def runTests(CheribuildProjectParams proj) {
	def imageName
	if (proj.cpu == 'mips') {
		imageName = 'cheri256-cheribsd-mips'
	} else {
		// boot a world with a hybrid userspace (it contains all the necessary shared libs)
		// There is no need for the binaries to be CHERIABI
		imageName = "${proj.sdkCPU}-cheribsd-hybrid"
	}
	def testImageArg = ''
	if (proj.minimalTestImage) {
		testImageArg = "--disk-image /usr/local/share/cheribsd/cheribsd-minimal.img"
	}
	def cheribsdImage = docker.image("ctsrd/${imageName}:latest")
	cheribsdImage.pull()
	runCallback(proj, proj.beforeTests)
	cheribsdImage.inside('-u 0') {
		// ./boot_cheribsd.py --qemu-cmd ~/cheri/output/sdk256/bin/qemu-system-cheri --disk-image ./cheribsd-jenkins_bluehive.img.xz --kernel cheribsd-cheri-malta64-kernel.bz2 -i
		// TODO: allow booting the minimal bluehive disk-image
		def testCommand = "'export CPU=${proj.cpu}; " + proj.testScript.replaceAll('\'', '\\\'') + "'"
		ansiColor('xterm') {
			sh "wget https://raw.githubusercontent.com/RichardsonAlex/cheri-sdk-docker/master/cheribsd/boot_cheribsd.py -O /usr/local/bin/boot_cheribsd.py"
			runCallback(proj, proj.beforeTestsInDocker)
			sh "boot_cheribsd.py ${testImageArg} --test-command ${testCommand} --test-archive ${proj.tarballName} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}"
		}
		runCallback(proj, proj.afterTestsInDocker)
	}
	runCallback(proj, proj.afterTests)
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

	if (proj.skipInitialSetup) {
		proj.skipScm = true
		proj.skipArtifacts = true
	}
	if (!proj.skipScm) {
		stage("Checkout") {
			echo "Target CPU: ${proj.cpu}, SDK CPU: ${proj.sdkCPU}, output: ${proj.tarballName}"
			// def sdkImage = docker.image("ctsrd/cheri-sdk-${proj.sdkCPU}:latest")
			// sdkImage.pull() // make sure we have the latest available from Docker Hub
			runCallback(proj, proj.beforeSCM)

			dir(proj.customGitCheckoutDir ? proj.customGitCheckoutDir : proj.target) {
				def x = checkout scm
				echo("${x}")
			}
			dir('cheribuild') {
				git 'https://github.com/CTSRD-CHERI/cheribuild.git'
			}
		}
	}
	if (!proj.skipArtifacts) {
		stage("Setup SDK for ${proj.target} (${proj.cpu})") {
			// now copy all the artifacts
			for (artifacts in proj.artifactsToCopy) {
				copyArtifacts projectName: artifacts.job, filter: artifacts.filter, fingerprintArtifacts: true
			}
			if (proj.needsFullCheriSDK) {
				copyArtifacts projectName: "CHERI-SDK/ALLOC=jemalloc,ISA=vanilla,SDK_CPU=${proj.sdkCPU},label=${proj.label}", filter: '*-sdk.tar.xz', fingerprintArtifacts: true
				ansiColor('xterm') {
					sh "./cheribuild/jenkins-cheri-build.py extract-sdk --cpu ${proj.cpu} ${proj.extraArgs}"
				}
			}
			echo 'WORKSPACE after checkout:'
			sh 'ls -la'
		}
	}
	stage("Build ${proj.target} for ${proj.cpu}") {
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
		step([
				$class: 'GitHubCommitStatusSetter',
				errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
				statusResultSource: [
						$class: 'ConditionalStatusResultSource',
						results: [
								[$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: message],
								[$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'UNSTABLE', message: message],
								[$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: message],
								[$class: 'AnyBuildResult', state: 'FAILURE', message: 'Loophole']
						]
				]
		])
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
			return
		} catch (IllegalArgumentException e) {
			error("cheribuildProject: Bad value ${it.value} for argument ${it.key}: ${e.getMessage()}")
			return
		} catch (e) {
			error("cheribuildProject: Could not set argument ${it.key} to ${it.value}: ${e}")
			return
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
}

// This is what gets called from jenkins
def call(Map args) {
	// just call the real method here so that I can run the tests
	// the problem is that if I invoke call I get endless recursion
	runCheribuild(args)
}

// TODO: this should move to a separate file but it doesn't seem possible with PipelineUnit
if (env.get("RUN_UNIT_TESTS")) {
	runCheribuild(target: "newlib-baremetal", cpu: "mips", extraArgs: '--install-prefix=/')
	def doBuild = { args ->
		def commonArgs = [
				target: 'libcxxrt',
				nodeLabel: null,
				skipScm: true,  // only the first run handles the SCM
				extraArgs: '--install-prefix=/']
		// runCheribuild(commonArgs + args)
	}

	node('linux') {
		doBuild(target: 'libcxxrt-baremetal', cpu: 'mips', skipScm: false,
				artifactsToCopy: [[job: 'Newlib-baremetal-mips/master', filter: 'newlib-baremetal-mips.tar.xz']],
				beforeBuild: 'mkdir -p cherisdk/baremetal && tar xzf newlib-baremetal-mips.tar.xz -C cherisdk/baremetal; ls -laR cheribsd/baremetal')
		doBuild([cpu: 'mips', skipArtifacts: true]) // we can reuse artifacts from last build
		doBuild([target: 'libcxxrt', cpu: 'cheri128', skipScm: true])
		doBuild([target: 'libcxxrt', cpu: 'cheri256', skipScm: true])
		doBuild([target: 'libcxxrt', cpu: 'native', skipScm: true])
	}
}
