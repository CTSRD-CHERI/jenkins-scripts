import groovy.json.*

class CheribuildProjectParams implements Serializable {
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
	def beforeBuildOutsideDocker  // callback before starting docker
	def beforeBuild  // first command inside docker
	def beforeTarball  // after building but before creating the tarball
	def afterBuildInDocker  // after building and tarball (no longer inside docker)
	def afterBuild  // after building and tarball (no longer inside docker)
	def beforeTestsOutsideDocker // before running the tests (inside docker)
	def beforeTests // before running the tests (inside docker)
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
	runCallback(proj, proj.beforeBuildOutsideDocker)
	// No docker yet
	// sdkImage.inside('-u 0') {
		env.CPU = proj.cpu
		ansiColor('xterm') {
			sh "rm -fv ${proj.tarballName}; pwd"
			runCallback(proj, proj.beforeBuild)
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
	sh 'ls -lah'
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
	runCallback(proj, proj.beforeTestsOutsideDocker)
	cheribsdImage.inside('-u 0') {
		// ./boot_cheribsd.py --qemu-cmd ~/cheri/output/sdk256/bin/qemu-system-cheri --disk-image ./cheribsd-jenkins_bluehive.img.xz --kernel cheribsd-cheri-malta64-kernel.bz2 -i
		// TODO: allow booting the minimal bluehive disk-image
		def testCommand = "'export CPU=${proj.cpu}; " + proj.testScript.replaceAll('\'', '\\\'') + "'"
		ansiColor('xterm') {
			sh "wget https://raw.githubusercontent.com/RichardsonAlex/cheri-sdk-docker/master/cheribsd/boot_cheribsd.py -O /usr/local/bin/boot_cheribsd.py"
			runCallback(proj, proj.beforeTests)
			sh "boot_cheribsd.py ${testImageArg} --test-command ${testCommand} --test-archive ${proj.tarballName} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}"
		}
		runCallback(proj, proj.afterTestsInDocker)
	}
	runCallback(proj, proj.afterTests)
}

def runCheribuild(CheribuildProjectParams proj) {
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
	}

	echo new JsonBuilder( proj ).toPrettyString()
	stage("Checkout and copy artifacts") {
		echo "Target CPU: ${proj.cpu}, SDK CPU: ${proj.sdkCPU}, output: ${proj.tarballName}"
		// def sdkImage = docker.image("ctsrd/cheri-sdk-${proj.sdkCPU}:latest")
		// sdkImage.pull() // make sure we have the latest available from Docker Hub
		runCallback(proj, proj.beforeSCM)

		dir(proj.customGitCheckoutDir ? proj.customGitCheckoutDir : proj.target) {
			checkout scm
		}
		dir('cheribuild') {
			git 'https://github.com/CTSRD-CHERI/cheribuild.git'
		}

		// now copy all the artifacts
		for (artifacts in proj.artifactsToCopy) {
			copyArtifacts projectName: artifacts.project, filter: artifacts.filter, fingerprintArtifacts: true
		}
		if (proj.needsFullCheriSDK) {
			copyArtifacts projectName: "CHERI-SDK/ALLOC=jemalloc,ISA=vanilla,SDK_CPU=${proj.sdkCPU},label=${proj.label}", filter: '*-sdk.tar.xz', fingerprintArtifacts: true
		}
		echo 'WORKSPACE after checkout:'
		sh 'ls -la'
	}
	stage("Build ${proj.target} for ${proj.cpu}") {
		build(proj)
	}
	if (proj.testScript) {
		stage("run tests for ${proj.cpu}") {
			runTests(proj)
		}
	}
	// TODO: clean up properly and remove the created artifacts?
}

// This is what gets called from jenkins
def call(Map args) {
	node('docker') {
		// The map spread operator is not supported in Jenkins
		// def project = new CheribuildProjectParams(target: args.name, *:args)
		def config = args as CheribuildProjectParams
		runCheribuild(config)
	}
}

// for testing:
// call(target:"newlib-baremetal", cpu:"mips")