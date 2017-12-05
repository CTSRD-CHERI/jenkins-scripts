class JobConfig {
	String buildArgs
	String testArgs
	String assembler = "binutils"
	String name = null
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
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 STATCOUNTERS=1 ALLOW_UNALIGNED=1 SIM_TRACE_OPTS= nosetests_combined.xml'],
			"CHERI1-CACHECORE-TEST": [
					buildArgs: 'CAP=True ICACHECORE=1 DCACHECORE=1',
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-FPU-TEST": [
					buildArgs: 'CAP=True COP1=1',
					testArgs: 'COP1=1 TEST_PS=1 CLANG=0 NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_combined.xml'],
			"CHERI1-CAP128-TEST": [
					buildArgs: 'CAP128=True',
					testArgs: 'GENERIC_L1=1 CAP_SIZE=128 PERM_SIZE=19 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-MICRO-TEST": [
					buildArgs: 'MICRO=True CAP=True NOWATCH=True',
					testArgs: 'CHERI_MICRO=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached'],
			"CHERI1-MULTI1-TEST": [
					buildArgs: 'MULTI=1 CAP=True',
					testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_cached.xml'],
			"CHERI1-MULTI2-TEST": [
					buildArgs: 'MULTI=2 CAP=True',
					testArgs: 'GENERIC_L1=1 MULTI=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml'],
	]
	Map result = config.get(jobName)
	if (!result) {
		error("No configuration found for job ${jobName}! Please add one to the Map above")
	}
	result.name = jobName
	return result as JobConfig
}

def doBuild(JobConfig args) {
	return timeout(240) {
		if (args.assembler != 'clang') {
			copyArtifacts filter: 'binutils.tar.bz2', fingerprintArtifacts: true, projectName: 'CHERI-binutils/label=linux/'
		}
		def clangValue = args.assembler == 'clang' ? '1' : '0'
		stage('Build Simulator') {
			// NOPRINTS=1 might to be required for successful builds? At least for CACHECORE
			// This should speed up running the tests:
			args.buildArgs += ' NOPRINTS=1'
			sh '''#!/bin/bash
set -xe
. /local/ecad/setup.bash \$QUARTUS_DEFAULT
tar xjf binutils.tar.bz2
#export PATH=\$WORKSPACE/binutils/bin:\$CHERITEST_TOOL_PATH:$PATH
export PATH=\$WORKSPACE/binutils/bin:\$PATH
cd ctsrd/cheri/trunk
make clean
#rm sim sim.so
# build the simulator
''' + "make ${args.buildArgs} sim || (make clean ; make ${args.buildArgs} sim)"
		}
		stage('Tests') {
			sh '''#!/bin/bash
set -xe
cd $WORKSPACE/ctsrd/cheritest/trunk
make clean
# Rebuild the clang tests every time, in case clang itself has changed
rm -f obj/*clang* log/*clang*
''' + "make CLANG=${clangValue} ${args.testArgs} -j8 || true"
			archiveArtifacts allowEmptyArchive: false, artifacts: 'ctsrd/cheri/trunk/sim, ctsrd/cheri/trunk/sim.so, ctsrd/cheri/trunk/sim.dtb, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.so, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.dtb, ctsrd/cherilibs/trunk/peripherals/*.so, ctsrd/cherilibs/trunk/tools/memConv.py', caseSensitive: true, defaultExcludes: true, excludes: 'ctsrd/cheritest/**/*', fingerprint: false, onlyIfSuccessful: true
			// JUnit Results
			junit 'ctsrd/cheritest/trunk/nosetests_*.xml'
		}
	}
}

def cheriHardwareTest() {
	return node('llvm&&bluespec') {
		echo "Computing job parameters for ${env.JOB_NAME}"
		JobConfig args = getJobParameters()
		echo "Found job config: BUILD_ARGS: '${args.buildArgs}'\nTEST_ARGS: '${args.testArgs}'\nASSEMBLER: ${args.assembler}"
		stage('Checkout') {
			// dir('ctsrd/cheritest/trunk') { git url: 'git@github.com:CTSRD-CHERI/cheritest.git', credentialsId: 'cheritest_key', branch: 'master'}
			dir('ctsrd/cheritest/trunk') {
				checkout scm  // get the sources from git
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
		doBuild(args)
	}
}

try {
	cheriHardwareTest()
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
