import groovy.json.*

enum BuildResult {
	PENDING, SUCCESS, UNSTABLE, FAILURE
}

class CheribuildProjectParams implements Serializable {
	List targetArchitectures = []
	boolean skipScm = false
	boolean deleteAfterBuild = true
	// Whether to skip the clone/copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipArtifacts = false
	// Whether to skip the copy artifacts stage (useful if there are multiple cheribuild invocations)
	boolean skipInitialSetup = false
	// skip both the copy artifacts and clone stage
	boolean skipTarball = false // don't create a tarball to archive
	boolean skipArchiving = false // don't archive the artifacts
	String nodeLabel = "linux"
	// if non-null allocate a new jenkins node using node()
	String uniqueId = null // Used for the github/analysis ID
	String buildOS = null
	// Used when copying artifacts (the label parameter for those other jobs)
	BuildResult _result = BuildResult.PENDING // For github status updates
	String getResult() { return _result }

	void setResult(BuildResult newResult) {
		if (newResult.ordinal() < _result.ordinal()) {
			echo("Cannot change build result from ${_result} to ${newResult}")
			throw new IllegalArgumentException("Cannot change build result from ${_result} to ${newResult}")
		} else {
			_result = newResult
		}
	}

	void setResult(String newResult) {
		setResult(newResult as BuildResult)
	}
	Closure callGlobalUnstable = null;

	void statusUnstable(String message) {
		setResult(BuildResult.UNSTABLE)
		this.callGlobalUnstable(message)
	}
	Closure callGlobalError = null;

	void statusFailure(String message) {
		setResult(BuildResult.FAILURE)
		this.callGlobalError(message)
	}
	boolean setGitHubStatus = true
	String gitHubStatusContext = null

	Object scmOverride = null
	// Set this to use something other than the default scm variable for checkout
	Object gitInfo = [:]
	// This will be set the the git info from the checkout stage
	Map gitInfoMap = null
	// Can be used to pass in a map that will be updated with the git info
	/// Custom gitInfo object for GitHub status updates (if the status should be
	/// placed on a different repo than the one we are building
	Object gitHubStatusArgs = null

	Map getRepoInfoForGitHubStatus() {
		return gitHubStatusArgs != null ? gitHubStatusArgs : gitInfo
	}

	/// general/build parameters
	String target // the cheribuild project name
	String _targetWithoutSuffix  // Set when building multiple architectures
	String extraArgs = '' // additional arguments to pass to cheribuild.py
	String cpu = 'default' // --cpu flag for cheribuild (deprecated)
	String architecture // suffix to be used for all output files, etc.
	String sysrootArchitecture // sysroot architecture (e.g. riscv64-purecap for foo-hybrid-for-purecap-rootfs)
	String capTableABI = null // use whatever the default is
	boolean fetchCheriCompiler = true
	boolean sdkCompilerOnly = false
	String llvmBranch = null
	// Git branch of LLVM to use for building. When NULL infer from branch name.
	// otherwise pull just a specific set of artifacts
	String cheribsdBranch = 'main' // Branch of CheriBSD to use for the disk images/sysroot
	String cheribuildBranch = null  // Branch of cheribuild to use for building
	List artifactsToCopy = []
	// List of (job:filter) for artifacts which need copying
	String tarballName
	// output tarball name (default is "${target}-${cpu}.tar.xz")
	String customGitCheckoutDir
	// by default we try to do an incremental build and if that fails fall back to a full build
	// FIXME: not sure this is actually working, it seems to rebuild all the time
	boolean incrementalBuild = false
	// whether to force a clean build (i.e. don't pass --no-clean to cheribuild)

	/// Test parameters:
	def testTimeout = 120 * 60 // timeout for running tests (default 2 hours)
	boolean minimalTestImage = true
	boolean runTests = false
	boolean useCheriKernelForTests = true
	String testScript
	// if set this will be invoked by ./boot_cheribsd.py in the test stage. If not tests are skipped
	String testExtraArgs = ''
	// Additional command line options to be passed to ./boot_cheribsd.py
	boolean runTestsInDocker = false
	// Seems to be really slow (1 min 44 until init instead of 15 secs)
	String junitXmlFiles = null
	// Pattern for junit() step to record test results
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
	def afterBuildInDocker
	// after building and tarball (no longer inside docker)
	def afterBuild  // after building and tarball (no longer inside docker)
	def beforeTests // before running the tests (before docker)
	def beforeTestsInDocker // before running the tests (inside docker)
	// def afterTestsInCheriBSD // before running the tests (sent to cheribsd command line)
	def afterTestsInDocker
	// before running the tests (inside docker, cheribsd no longer running)
	def afterTests // before running the tests (no longer inside docker)

	String commonCheribuildArgs() {
		def result = "${this.target} ${this.extraArgs}"
		if (this.capTableABI) {
			result += " --cap-table-abi=${this.capTableABI}"
		}
		return result
	}
}

// FIXME: all this jenkins transforming stuff is ugly... how can I access the jenkins globals?

boolean updatePRStatus(CheribuildProjectParams proj, String message, String status = null) {
	if (!env.CHANGE_ID) {
		return false
	}
	if (!proj.setGitHubStatus) {
		return false
	}
	try {
		if (!status) {
			status = proj._result.name().toLowerCase()
			if (status == 'failure') {
				status = 'error'
			} else if (status == 'unstable') {
				status = 'failure'
			}
		}
		echo("Setting PR${env.CHANGE_ID} status: ${status} for ${proj.gitHubStatusContext}: ${message}")
		pullRequest.createStatus(status: status,
				context: proj.gitHubStatusContext,
				description: message,
				targetUrl: "${env.RUN_DISPLAY_URL}")
		// TODO: CHANGE_URL? or BUILD_URL?
	} catch (e) {
		error("Failed to set PR status: ${e}")
		return false;
	}
	return true;
}

// Run a beforeXXX hook (beforeBuild, beforeTarball, etc.)
def runCallback(CheribuildProjectParams proj, cb) {
	// def cb = this."${hook}"
	if (!cb) {
		return
	}
	// echo "Running callback ${hook}"
	if ((cb instanceof Closure) || cb.metaClass.respondsTo(cb, 'call')) {
		//noinspection GroovyAssignabilityCheck
		cb(proj)
	} else {
		echo("Callback type: " + cb.getClass().getCanonicalName())
		def callbackString = "${cb}"
		if (!callbackString.allWhitespace) {
			sh label: 'callback', script: cb
		}
	}
}

def build(CheribuildProjectParams proj, String stageSuffix) {
	// No docker yet
	// sdkImage.inside('-u 0') {
	ansiColor('xterm') {
		def buildStage = proj.buildStage ? proj.buildStage : "Build ${proj.target} ${stageSuffix}"
		stage(buildStage) {
			sh "rm -fv ${proj.tarballName}; pwd"
			runCallback(proj, proj.beforeBuildInDocker)
			def cheribuildCmd = "./cheribuild/jenkins-cheri-build.py --build ${proj.commonCheribuildArgs()}"
			// By default do a full rebuild. This can be disabled by passing incrementalBuild: true
			if (proj.incrementalBuild) {
				sh label: "Building with cheribuild (incremental) ${stageSuffix} on ${env.NODE_LABELS}",
						script: "${cheribuildCmd} --no-clean || (echo 'incremental build failed!' && ${cheribuildCmd})"
			} else {
				sh label: "Building with cheribuild ${stageSuffix} on ${env.NODE_LABELS}", script: "${cheribuildCmd}"
			}
		}
		runCallback(proj, proj.afterBuildInDocker)
	}
	// }
	runCallback(proj, proj.afterBuild)
}

def runTestsImpl(CheribuildProjectParams proj, String testExtraArgs, String testSuffix) {
	ansiColor('xterm') {
		def testExitCode
		runCallback(proj, proj.beforeTestsInDocker)
		if (proj.testScript && proj.architecture != 'native') {
			def testCommand = "'export CPU=${proj.cpu}; " + proj.testScript.replaceAll('\'', '\\\'') + "'"
			echo "Test command = ${testCommand}"
			testExitCode = sh returnStatus: true, label: "Running simple test (${testSuffix})",
					script: "\$WORKSPACE/cheribuild/test-scripts/run_simple_tests.py ${testExtraArgs} --test-command ${testCommand} --test-archive ${proj.tarballName} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}"
		} else {
			String cheribuildTestArgs = (proj.architecture == 'native') ? "${testExtraArgs} ${proj.testExtraArgs}" :
										"--test-extra-args=\"${testExtraArgs} --test-timeout ${proj.testTimeout} ${proj.testExtraArgs}\""
			testExitCode = sh returnStatus: true, label: "Running tests with cheribuild ${testSuffix}",
					script: "\$WORKSPACE/cheribuild/jenkins-cheri-build.py --test ${proj.commonCheribuildArgs()} ${cheribuildTestArgs}"
		}
		if (testExitCode != 0) {
			proj.statusUnstable("Test script returned ${testExitCode}")
		}
		runCallback(proj, proj.afterTestsInDocker)
	}
}

def runTests(CheribuildProjectParams proj, String testSuffix) {
	updatePRStatus(proj, "Running tests for PR...")
	// Custom test script only support for CheriBSD
	String testCPU = proj.architecture
	if (proj.testScript && testCPU != "mips64" && testCPU != "mips64-hybrid" && testCPU != "mips64-purecap") {
		error("Running tests for target ${proj.architecture} not supported yet")
	}

	if ((testCPU == 'mips64' || testCPU == 'riscv64') && proj.useCheriKernelForTests) {
		testCPU += '-hybrid'
	}
	def defaultTestExtraArgs = ''
	if (testCPU != "native") {
		def diskImageProjectName = "CheriBSD-pipeline/${proj.cheribsdBranch}"
		sh "rm -rfv artifacts-${testCPU}/cheribsd-*.img* artifacts-${testCPU}/kernel*"
		def compressedKernel = "artifacts-${testCPU}/kernel.xz"
		def compressedDiskImage
		if (proj.minimalTestImage) {
			// TODO: use the MFS_ROOT kernel instead
			compressedDiskImage = "artifacts-${testCPU}/cheribsd-minimal-${testCPU}.img.xz"
			copyArtifacts projectName: diskImageProjectName,
					filter: "${compressedDiskImage}, ${compressedKernel}",
					target: '.', fingerprintArtifacts: false, flatten: false, selector: lastSuccessful()
			defaultTestExtraArgs = "--minimal-image "
		} else {
			compressedDiskImage = "artifacts-${testCPU}/cheribsd-${testCPU}.img.xz"
			copyArtifacts projectName: diskImageProjectName,
				filter: "${compressedDiskImage}, ${compressedKernel}",
				target: '.', fingerprintArtifacts: false, flatten: false, selector: lastSuccessful()
		}
		defaultTestExtraArgs += "--kernel ${compressedKernel} --no-keep-compressed-images"
		if (compressedDiskImage) {
			defaultTestExtraArgs += " --disk-image ${compressedDiskImage}"
		}
		sh "ls -la \$WORKSPACE"
	}

	runCallback(proj, proj.beforeTests)
	if (proj.runTestsInDocker) {
		// Try to speed it up by extracting to tmpfs
		defaultTestExtraArgs += " --extract-images-to /images"
		def cheribsdImage = docker.image("ctsrd/qemu-cheri:latest")
		cheribsdImage.pull()
		// Currently the full image is 5.59G
		cheribsdImage.inside('-u 0 --rm --tmpfs /images:rw,noexec,nosuid,size=7g') {
			sh 'df -h /images'
			runTestsImpl(proj, defaultTestExtraArgs, testSuffix)
		}
	} else {
		if (testCPU != "native") {
			// copy qemu archive and run directly on the host
			dir("qemu-${proj.buildOS}") { deleteDir() }
			def qemuProject = testCPU.contains('morello') ? 'qemu/qemu-morello-merged' : 'qemu/qemu-cheri'
			copyArtifacts projectName: qemuProject, filter: "qemu-${proj.buildOS}/**", target: '.',
					fingerprintArtifacts: false
			sh label: 'generate SSH key',
					script: 'test -e $WORKSPACE/id_ed25519 || ssh-keygen -t ed25519 -N \'\' -f $WORKSPACE/id_ed25519 < /dev/null'
			defaultTestExtraArgs += " --ssh-key \$WORKSPACE/id_ed25519.pub"
		}
		runTestsImpl(proj, defaultTestExtraArgs, testSuffix)
	}
	runCallback(proj, proj.afterTests)
	if (proj.junitXmlFiles != null) {
		def testSummary = junitReturnCurrentSummary allowEmptyResults: false, keepLongStdio: true,
				testResults: proj.junitXmlFiles
		echo("Test results: ${testSummary.totalCount}, Failures: ${testSummary.failCount}, Skipped: ${testSummary.skipCount}, Passed: ${testSummary.passCount}")
		if (testSummary.passCount == 0 || testSummary.totalCount == 0) {
			proj.statusFailure("No tests successful?")
		} else if (testSummary.failCount != 0) {
			// Note: Junit set should have set stage/build status to unstable already, but we still need to set
			// the per-configuration status, since Jenkins doesn't have a build result for each parallel branch.
			proj.statusUnstable("${testSummary.failCount} test(s) failed!")
		}
	}
}

def runCheribuildImpl(CheribuildProjectParams proj) {
	// Note: env.FOO = ... sets the environment globally across all nodes
	// so it cannot be used if we want to support cheribuildProject inside
	// parallel blocks
	// echo("env before =${env}")
	withEnv(["CPU=${proj.cpu}", "SDK_CPU=${proj.architecture}", "CHERIBUILD_ARCH=${proj.architecture}"]) {
		// echo("env in block=${env}")
		if (!proj.uniqueId) {
			proj.uniqueId = "${currentBuild.projectName}/${proj.target}"
			if (proj.nodeLabel) {
				proj.uniqueId += "/${proj.nodeLabel}"
			}
			while (CheribuildProjectParams.uniqueIDs.containsKey(proj.uniqueId.toString())) {
				proj.uniqueId += "_1"
			}
			CheribuildProjectParams.uniqueIDs.put(proj.uniqueId, proj.uniqueId)
		}
		if (!proj.gitHubStatusContext) {
			proj.gitHubStatusContext = "jenkins/${proj.uniqueId}"
		}
		if (env.CHANGE_ID && !shouldBuildPullRequest(context: proj.gitHubStatusContext)) {
			echo "Not building this pull request."
			return
		}
		try {
			runCheribuildImplWithEnv(proj)
			// If the status has not been changed (i.e. to UNSTABLE/FAILURE) it means we SUCCEEDED
			if (proj._result == BuildResult.PENDING) {
				proj._result = BuildResult.SUCCESS
			}
		} catch (e) {
			// e.printStackTrace()
			echo("Marking current build as failed! (${e}:${e.getMessage()})")
			proj._result = BuildResult.FAILURE
			throw e
		} finally {
			// In the single project case propagate the unstable flag:
			// if (proj.targetArchitectures.size() == 1 && proj._result == BuildResult.SUCCESS && "${currentBuild.currentResult}" == "UNSTABLE") {
			//	 proj._result = BuildResult.UNSTABLE
			// }
			if (proj._result == BuildResult.PENDING) {
				proj.statusFailure("RESULT IS STILL PENDING! Something is very wrong...")
			}
			if (proj.setGitHubStatus) {
				echo("Setting github status after build\nproj.result=${proj.result}, currentBuild.result=${currentBuild.result} currentBuild.currentResult=${currentBuild.currentResult}")
				if (!updatePRStatus(proj, "Finished (${proj.result}).") && proj.setGitHubStatus) {
					def message = "${currentBuild.projectName}"
					if (proj.nodeLabel) {
						message += " ${proj.nodeLabel}"
					}
					setGitHubStatus(proj.getRepoInfoForGitHubStatus() +
									[message: message, result: proj.result, context: proj.gitHubStatusContext])
				}
			}
		}
	}
}

def runCheribuildImplWithEnv(CheribuildProjectParams proj) {
	def gitHubCommitSHA = null
	def gitHubRepoURL = null
	updatePRStatus(proj, "Starting build...")

	if (proj.skipInitialSetup) {
		proj.skipScm = true
		proj.skipArtifacts = true
	}
	stage("Checkout") {
		if (!proj.buildOS) {
			proj.buildOS = inferBuildOS()
			echo("Inferred build OS: '${proj.buildOS}'")
		}
		if (!proj.skipScm) {
			echo "Target arch: ${proj.architecture}, output: ${proj.tarballName}"
			// def sdkImage = docker.image("ctsrd/cheri-sdk:latest")
			// sdkImage.pull() // make sure we have the latest available from Docker Hub
			runCallback(proj, proj.beforeSCM)

			def gitCloneDir = proj.customGitCheckoutDir
			// Don't add a suffix to the git checkout dir:
			// TODO: should do something like cheribuild --print-default-source-dir <target> instead
			if (!gitCloneDir) {
				gitCloneDir = proj._targetWithoutSuffix != null ? proj._targetWithoutSuffix : proj.target
			}
			dir(gitCloneDir) {
				def realScm = proj.scmOverride != null ? proj.scmOverride : scm
				proj.gitInfo = checkout realScm
				echo("Checkout result: ${proj.gitInfo}")
				gitHubCommitSHA = proj.gitInfo?.GIT_COMMIT
				gitHubRepoURL = proj.gitInfo?.GIT_URL
				if (proj.gitInfoMap != null) {
					proj.gitInfoMap << proj.gitInfo
				}
				// store it so it exists even on exception
			}
		}
		dir('cheribuild') {
			cheribuildCloneArgs = [url: "https://github.com/CTSRD-CHERI/cheribuild.git",
					       changelog: false, poll: false, refdir: "cheribuild"]
			if (proj.cheribuildBranch) {
				cheribuildCloneArgs['branches'] = '*/' + proj.cheribuildBranch
			}
			def x = cloneGitRepoWithReference(cheribuildCloneArgs)
			echo("Checked out cheribuild: ${x}")
		}
	}
	if (proj.setGitHubStatus && !updatePRStatus(proj, "About to build PR...", 'pending') && proj.setGitHubStatus) {
		setGitHubStatus(proj.getRepoInfoForGitHubStatus() +
						[message: "${currentBuild.projectName} building ...", context: proj.gitHubStatusContext])
	}
	runCallback(proj, proj.beforeBuild)
	if (!proj.skipArtifacts) {
		stage("Copying required artifacts") {
			// Can't use a for loop here: https://issues.jenkins-ci.org/browse/JENKINS-49732
			proj.artifactsToCopy.each { artifacts ->
				copyArtifacts projectName: artifacts.job, filter: artifacts.filter, fingerprintArtifacts: false
			}
			// now copy all the artifacts
			if (proj.fetchCheriCompiler) {
				fetchCheriSDK(target: proj.target, cpu: proj.sysrootArchitecture,
						compilerOnly: proj.sdkCompilerOnly, llvmBranch: proj.llvmBranch,
						cheribsdBranch: proj.cheribsdBranch,
						buildOS: proj.buildOS, capTableABI: proj.capTableABI,
						extraCheribuildArgs: proj.extraArgs)
			}
			sh label: 'WORKSPACE after checkout:', script: 'ls -lah'
		}
	}
	def buildSuffix = proj.stageSuffix ? proj.stageSuffix : ""
	build(proj, buildSuffix)
	if (proj.testScript || proj.runTests) {
		def testSuffix = proj.stageSuffix ? proj.stageSuffix : ""
		stage("Run ${proj.target} tests ${testSuffix}") {
			runTests(proj, testSuffix)
		}
	}
	def analysisId = proj.stageSuffix ? proj.stageSuffix : "${proj.uniqueId}"
	analysisId = analysisId.replaceAll('[^\\p{Alnum}-_.]', '_')
	warnError("FAILED TO RECORD ISSUES") {
		recordIssues aggregatingResults: true, blameDisabled: true, enabledForFailure: true, forensicsDisabled: true,
				sourceCodeEncoding: 'UTF-8', tools: [clang(reportEncoding: 'UTF-8', id: "clang-${analysisId}")]
	}
	if (!proj.skipTarball) {
		stage("Creating tarball for ${proj.target}") {
			runCallback(proj, proj.beforeTarball)
			sh label: "Create tarball ${buildSuffix}",
					script: "./cheribuild/jenkins-cheri-build.py --tarball --tarball-name ${proj.tarballName} ${proj.commonCheribuildArgs()}"
			sh label: "List tarball ${buildSuffix}", script: 'ls -lah; ls -lah tarball || true'
		}
		if (!proj.skipArchiving) {
			stage("Archiving artificats for ${proj.target}") {
				if (!updatePRStatus(proj, "Archiving artifacts...") && proj.setGitHubStatus) {
					setGitHubStatus(proj.getRepoInfoForGitHubStatus() +
									[message: "${currentBuild.projectName} archiving artifacts ...",
									 context: proj.gitHubStatusContext])
				}
				archiveArtifacts allowEmptyArchive: false, artifacts: proj.tarballName, fingerprint: true,
						onlyIfSuccessful: true
			}
		}
	}
}

CheribuildProjectParams parseParams(Map args) {
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
	if (!params.cpu) {
		params.cpu = "default"
	}
	if (!params.architecture) {
		if (params.target.endsWith('-mips-nocheri') || params.target.endsWith('-mips64')) {
			params.architecture = 'mips64'
			// Handle old hybrid target names
		} else if (params.target.endsWith('-mips-hybrid') || params.target.endsWith('-mips64-hybrid') ||
				   params.target == 'cheribsd-cheri' || params.target == 'disk-image-cheri' || params.target ==
				   'run-cheri') {
			params.architecture = 'mips64-hybrid'
		} else if (params.target.endsWith('-mips-purecap') || params.target.endsWith('-mips64-purecap') ||
				   params.target.endsWith('-cheri')) {
			params.architecture = 'mips64-purecap'
		} else if (params.target.endsWith('-riscv64')) {
			params.architecture = 'riscv64'
		} else if (params.target.endsWith('-riscv64-hybrid')) {
			params.architecture = 'riscv64-hybrid'
		} else if (params.target.endsWith('-riscv64-purecap')) {
			params.architecture = 'riscv64-purecap'
		} else if (params.target.endsWith('-purecap')) { // legacy target names
			params.architecture = 'mips64-purecap'
		} else if (params.target.endsWith('-native')) { // legacy target names
			params.architecture = 'native'
		}
	}
	if (!params.architecture) {
		if (params.cpu == 'mips') {
			params.architecture = 'mips64'
		} else if (params.cpu == 'hybrid-128') {
			params.architecture = 'mips64-hybrid'
		} else if (params.cpu == 'cheri128') {
			params.architecture = 'mips64-purecap'
		} else if (params.cpu == 'native') {
			params.architecture = 'native'
		}
	}
	if (!params.architecture) {
		error("Could not infer 'architecture' parameter from target (${params.target}) or cpu (${params.cpu})")
	}
	// Canonicalize architecure:
	if (params.architecture == 'purecap') {
		params.architecture = 'mips64-purecap'
	}
	if (params.architecture == 'cheri') {
		if (params.target.startsWith('cheribsd') || params.target.startsWith('disk-image') ||
			params.target.startsWith('run')) {
			params.architecture = 'mips64-hybrid'
		} else {
			params.architecture = 'mips64-purecap'
		}
	}
	if (!params.sysrootArchitecture) {
		// By default the sysroot architecture matches the architecture suffix, but if we are building
		// a target such as gdb-riscv64-hybrid-for-purecap-rootfs we have to use the purecap riscv sysroot.
		params.sysrootArchitecture = params.architecture
		// We assume that the base architecture never contains a dash.
		String baseArchitecture = params.sysrootArchitecture.split('-')[0];
		if (params.sysrootArchitecture.endsWith('for-purecap-rootfs')) {
			params.sysrootArchitecture = baseArchitecture + "-purecap"
		} else if (params.sysrootArchitecture.endsWith('for-hybrid-rootfs')) {
			params.sysrootArchitecture = baseArchitecture + "-hybrid"
		}
	}

	if (!params.tarballName) {
		// Don't add the target suffix to the tarball twice
		if (params.target.endsWith(params.architecture))
			params.tarballName = "${params.target}.tar.xz"
		else
			params.tarballName = "${params.target}-${params.architecture}.tar.xz"
	}
	// Skip archiving and tarballs for pull requests:
	if (env.CHANGE_ID) {
		params.skipArchiving = true
		params.skipTarball = true
		params.deleteAfterBuild = true
	}

	// WTF. Work around weird scoping rules. Groovy really sucks...
	params.callGlobalUnstable = { String message -> unstable(message) }
	params.callGlobalError = { String message -> error(message) }

	return params
}

def runCheribuild(CheribuildProjectParams params) {
	// The map spread operator is not supported in Jenkins
	// def project = new CheribuildProjectParams(target: args.name, *:args)
	if (params.nodeLabel != null) {
		node(params.nodeLabel) {
			try {
				runCheribuildImpl(params)
			} finally {
				// If we allocated a new node, clean up by default.
				// However, keep the dir if there was an error (for debugging).
				if (params.deleteAfterBuild && params._result != BuildResult.FAILURE) {
					deleteDir()
				}
			}
		}
	} else {
		runCheribuildImpl(params)
	}
	return params
}

// This is what gets called from jenkins
def call(Map args) {
	List targetArchitectures = args.getOrDefault('targetArchitectures', [])
	def failFast = args.getOrDefault('failFast', true) as Boolean
	args.remove('failFast')
	if (targetArchitectures.isEmpty()) {
		return runCheribuild(parseParams(args))
	}
	// Otherwise run multiple architectures in parallel
	def tasks = [failFast: failFast]
	targetArchitectures.each { String suffix ->
		tasks[suffix] = { ->
			String targetWithoutSuffix = args.getOrDefault('target', 'target must be set!')
			Map newMap = args + [target			  : targetWithoutSuffix + "-${suffix}",
								 _targetWithoutSuffix: targetWithoutSuffix,
								 architecture		: "${suffix}"]
			echo("newMap=${newMap}")
			// just call the real method here so that I can run the tests
			// the problem is that if I invoke call I get endless recursion
			def params = parseParams(newMap)
			return runCheribuild(params)
		}
	}
	if (env?.UNIT_TEST) {
		tasks.each { key, closure ->
			if (key == "failFast") {
				return
			}
			echo("Running ${key}")
			closure()
			echo("Finished running ${key}")
		}
	} else {
		parallel tasks
	}
}
