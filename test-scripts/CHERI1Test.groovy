class JobConfig {
	String buildArgs
	String testArgs
	String name = null
	static Object gitInfo = null

	// TODO: for some reason I can't make these globals, so let's just put them here
	static String clangArchiveName = 'cheri-multi-master-clang-llvm.tar.xz'
	static String clangJobName = 'CLANG-LLVM-master/CPU=cheri-multi,label=linux/'
	static String binutilsArchiveName = 'binutils.tar.bz2'
	static String binutilsJobName = 'CHERI-binutils/label=linux/'

}

// TODO: instead of this we could also have a Jenkinsfile per config and use JobDSL to generate one job per jenkinsfile
JobConfig getJobParameters() {
	String jobName = env.JOB_NAME
	if (jobName.contains('/')) {
		jobName = jobName.substring(0, jobName.indexOf('/'))
	}
	if (jobName.endsWith('-pipeline')) {
		jobName = jobName.substring(0, jobName.indexOf('-pipeline'))
	}
	echo "Computed base job names as $jobName"
	Map config = [
			"CHERI1-TEST": [buildArgs: 'CAP=True',
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 STATCOUNTERS=1 SIM_TRACE_OPTS= nosetest_cached nosetest'],
			"CHERI1-CACHECORE-TEST": [
					buildArgs: 'CAP=True ICACHECORE=1 DCACHECORE=1',
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-FPU-TEST": [
					buildArgs: 'CAP=True COP1=1',
					testArgs: 'COP1=1 CLANG=0 NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-CAP128-TEST": [
					buildArgs: 'CAP128=True',
					testArgs: 'GENERIC_L1=1 CAP_SIZE=128 PERM_SIZE=19 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached nosetest'],
			"CHERI1-MICRO-TEST": [
					buildArgs: 'MICRO=True CAP=True NOWATCH=True',
					testArgs: 'CHERI_MICRO=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-MULTI1-TEST": [
					buildArgs: 'MULTI=1 CAP=True',
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-MULTI2-TEST": [
					buildArgs: 'MULTI=2 CAP=True',
					testArgs: 'GENERIC_L1=1 MULTI=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml'],

			// BERI TESTS:

			"BERI1-TEST": [
					buildArgs: '',
					testArgs: 'TEST_CP2=0 GENERIC_L1=1 NOFUZZR=1 BERI=1 nosetest_cached'],

			"BERI1-MICRO-TEST": [
					buildArgs: 'MICRO=True NOWATCH=True',
					testArgs: 'TEST_CP2=0 CHERI_MICRO=1 NOFUZZR=1 WONTFIX=1 nosetest_cached'],

			"BERI1-MULTI1-TEST": [
					buildArgs: 'MULTI=1',
					testArgs: 'TEST_CP2=0 GENERIC_L1=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'],

			"BERI1-MULTI2-TEST": [
					buildArgs: 'MULTI=2',
					testArgs: 'MULTI=1 TEST_CP2=0 GENERIC_L1=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml'],

			"BERI1-MULTI2-TIMEBASED-TEST": [
					buildArgs: 'MULTI=2 TIMEBASED=1',
					testArgs: 'MULTI=1 TEST_CP2=0 GENERIC_L1=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml'],
	]
	Map result = config.get(jobName)
	if (!result) {
		error("No configuration found for job ${jobName}! Please add one to the Map above")
	}
	result.name = jobName
	return result as JobConfig
}


def runTests(JobConfig args, String assembler) {
	def prepareAssembler = ''
	def assemblerTestFlag = ''
	if (assembler == 'clang') {
		assemblerTestFlag = 'CLANG=1 CHERI_SDK=\$WORKSPACE/cherisdk'
		prepareAssembler = """
mkdir -p cherisdk
tar Jxf ${args.clangArchiveName} --strip-components 1 -C cherisdk
export CHERI_SDK=\$WORKSPACE/cherisdk"""
	} else {
		prepareAssembler = """
tar xjf ${args.binutilsArchiveName}
export PATH=\$WORKSPACE/binutils/bin:\$PATH"""
		assemblerTestFlag = 'CLANG=0'
	}
	stage("Tests (${assembler})") {
		sh """#!/bin/bash
set -xe
cd \$WORKSPACE
${prepareAssembler}

. /local/ecad/setup.bash \$QUARTUS_DEFAULT
cd \$WORKSPACE/ctsrd/cheritest/trunk
# always do a full clean in case the linker/compiler has changed
make clean
rm -f nose*.xml
make ${assemblerTestFlag} ${args.testArgs} -j16
"""
		// JUnit Results
		junit 'ctsrd/cheritest/trunk/nosetests_*.xml'
	}
}

def doBuild(JobConfig args) {
	if (args.name.startsWith('BERI')) {
		if (!args.testArgs.contains('TEST_CP2=0')) {
			error("BERI tests need TEST_CP2=0 set")
			return
		}
	}
	timeout(120) {
		copyArtifacts filter: args.binutilsArchiveName, fingerprintArtifacts: true, projectName: args.binutilsJobName
		copyArtifacts filter: args.clangArchiveName, fingerprintArtifacts: true, projectName: args.clangJobName
		stage('Build Simulator') {
			// NOPRINTS=1 might to be required for successful builds? At least for CACHECORE
			// This should speed up running the tests:
			args.buildArgs += ' NOPRINTS=1'
			// NOT_FLAT speeds up incremental builds but it might slow down the simulator
			// args.buildArgs += ' NOT_FLAT=1'
			sh '''#!/bin/bash
set -xe
. /local/ecad/setup.bash \$QUARTUS_DEFAULT

#export PATH=\$WORKSPACE/binutils/bin:\$CHERITEST_TOOL_PATH:$PATH
cd ctsrd/cheri/trunk
# make clean
rm -f sim sim.so
# build the simulator
''' + "make ${args.buildArgs} sim || (make clean ; make ${args.buildArgs} sim)"
			archiveArtifacts allowEmptyArchive: false, artifacts: 'ctsrd/cheri/trunk/sim, ctsrd/cheri/trunk/sim.so, ctsrd/cheri/trunk/sim.dtb, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.so, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.dtb, ctsrd/cherilibs/trunk/peripherals/*.so, ctsrd/cherilibs/trunk/tools/memConv.py', caseSensitive: true, defaultExcludes: true, excludes: 'ctsrd/cheritest/**/*', fingerprint: false, onlyIfSuccessful: true
		}
		timeout(45) {
			runTests(args, "binutils")
		}
		timeout(45) {
			runTests(args, "clang")
		}
		warnings canComputeNew: false, canResolveRelativePaths: false, consoleParsers: [[parserName: 'Clang (LLVM based)']]
		step([$class: 'AnalysisPublisher', canComputeNew: false])
	}
}

def setGitHubStatusBasedOnCurrentResult(JobConfig args) {
	def message = "${env.JOB_NAME}: ${currentBuild.description}"
	def githubCommitStatusContext = "jenkins/${env.JOB_NAME}"
	Map githubNotifierOptions = [
			$class: 'GitHubCommitStatusSetter',
			errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
			// errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
			contextSource: [$class: "ManuallyEnteredCommitContextSource", context: githubCommitStatusContext],
			/*statusResultSource: [
					$class: 'ConditionalStatusResultSource',
					results: [
							[$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: message],
							[$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'FAILURE', message: message],
							[$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: message],
							[$class: 'AnyBuildResult', message: 'Something went wrong', state: 'ERROR']
					]
			]*/
			// statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: 'SUCCESS']] ]
	]
	def gitHubCommitSHA = JobConfig.gitInfo?.GIT_COMMIT
	def gitHubRepoURL = JobConfig.gitInfo?.GIT_URL
	if (gitHubCommitSHA)
		githubNotifierOptions['commitShaSource'] = [$class: "ManuallyEnteredShaSource", sha: gitHubCommitSHA]
	if (gitHubRepoURL) {
		if (gitHubRepoURL.endsWith('.git')) {
			gitHubRepoURL = gitHubRepoURL.substring(0, gitHubRepoURL.indexOf('.git'))
		}
		githubNotifierOptions['reposSource'] = [$class: "ManuallyEnteredRepositorySource", url: gitHubRepoURL]
	}
	echo("${githubNotifierOptions}")
	step(githubNotifierOptions)
}

def cheriHardwareTest() {
	node('llvm&&bluespec') {
		echo "Computing job parameters for ${env.JOB_NAME}"
		JobConfig args = getJobParameters()
		echo "Found job config: BUILD_ARGS: '${args.buildArgs}'\nTEST_ARGS: '${args.testArgs}'"
		stage('Checkout') {
			// dir('ctsrd/cheritest/trunk') { git url: 'git@github.com:CTSRD-CHERI/cheritest.git', credentialsId: 'cheritest_key', branch: 'master'}
			dir('ctsrd/cheritest/trunk') {
				args.gitInfo = checkout scm  // get the sources from git
			}
			// Get the SVN sources:
			checkout([$class: 'SubversionSCM', additionalCredentials: [], excludedCommitMessages: '',
					excludedRegions: '', excludedRevprop: '', excludedUsers: '',
					filterChangelog: false, ignoreDirPropChanges: false, includedRegions: '',
					locations: [
							[credentialsId: 'cffd45a1-3d92-4d8e-a485-db5a5852fe70', depthOption: 'infinity',
									ignoreExternalsOption: true, local: 'ctsrd/cherilibs/trunk',
									remote: 'svn+ssh://secsvn@svn-ctsrd.cl.cam.ac.uk/ctsrd/cherilibs/trunk'],
							[credentialsId: 'cffd45a1-3d92-4d8e-a485-db5a5852fe70', depthOption: 'infinity',
									ignoreExternalsOption: true, local: 'ctsrd/cheri/trunk',
									remote: 'svn+ssh://secsvn@svn-ctsrd.cl.cam.ac.uk/ctsrd/cheri/trunk']],
					workspaceUpdater: [$class: 'UpdateUpdater']])
		}
		try {
			setGitHubStatusBasedOnCurrentResult(args) // set PENDING status
			doBuild(args)
		} finally {
			echo("result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
			// it seems like the currentBuild.Result will always be null (PENDING) unless I set it explicitly here
			if (currentBuild.result == null)
				currentBuild.result = currentBuild.currentResult
			echo("final result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
			setGitHubStatusBasedOnCurrentResult(args)
		}
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
	// XXXAR: maybe the timeout works better outside of a node() block
	timeout(120) {
		cheriHardwareTest()
	}
} catch (e) {
	error(e.toString())
	/* emailext body: '$DEFAULT_CONTENT',
		recipientProviders: [
			[$class: 'CulpritsRecipientProvider'],
			[$class: 'DevelopersRecipientProvider'],
			[$class: 'RequesterRecipientProvider']
		],
		replyTo: '$DEFAULT_REPLYTO',
		subject: '$DEFAULT_SUBJECT',
		to: '$DEFAULT_RECIPIENTS' */
}
