def doBuild(String buildArgs, String testArgs, String assembler) {
    timeout(120) {
        if (assembler != 'clang') {
            copyArtifacts filter: 'binutils.tar.bz2', fingerprintArtifacts: true, projectName: 'CHERI-binutils/label=linux/'
        }
        def clangValue = assembler == 'clang' ? '1' : '0'
        def script = '''#!/bin/bash
set -xe
. /local/ecad/setup.bash \$QUARTUS_DEFAULT
tar xjf binutils.tar.bz2
#export PATH=\$WORKSPACE/binutils/bin:\$CHERITEST_TOOL_PATH:$PATH
export PATH=\$WORKSPACE/binutils/bin:\$PATH
cd ctsrd/cheri/trunk
make clean
#rm sim sim.so
# build the simulator
''' + "make ${buildArgs} sim || (make clean ; make ${buildArgs} sim)" + '''
cd ../../..
cd ctsrd/cheritest/trunk
make clean
# Rebuild the clang tests every time, in case clang itself has changed
rm -f obj/*clang* log/*clang*
''' + "make CLANG=${clangValue} ${testArgs} nosetests_combined.xml -j8 || true"
        sh script
        archiveArtifacts allowEmptyArchive: false, artifacts: 'ctsrd/cheri/trunk/sim, ctsrd/cheri/trunk/sim.so, ctsrd/cheri/trunk/sim.dtb, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.so, ctsrd/cheri/trunk/build_cap_tags_0_sim/sim.dtb, ctsrd/cherilibs/trunk/peripherals/*.so, ctsrd/cherilibs/trunk/tools/memConv.py', caseSensitive: true, defaultExcludes: true, excludes: 'ctsrd/cheritest/**/*', fingerprint: false, onlyIfSuccessful: true
        // JUnit Results
        junit 'ctsrd/cheritest/trunk/nosetests_combined.xml'
    }
}

def cheriHardwareTest(Map args) {
    return node('llvm&&bluespec') {
        stage(args.name + ' - Checkout') {
            /* dir('ctsrd/cheritest/trunk') {
                git url: 'git@github.com:CTSRD-CHERI/cheritest.git', credentialsId: 'cheritest_key', branch: 'master'
            } */
            dir('ctsrd/cheritest/trunk') {
                checkout scm  // get the sources from git
            }
            // Get the SVN sources:
            checkout([$class          : 'SubversionSCM', additionalCredentials: [], excludedCommitMessages: '',
                      excludedRegions : '', excludedRevprop: '', excludedUsers: '',
                      filterChangelog : false, ignoreDirPropChanges: false, includedRegions: '',
                      locations       : [
                              [credentialsId        : 'cffd45a1-3d92-4d8e-a485-db5a5852fe70', depthOption: 'infinity',
                               ignoreExternalsOption: true, local: 'ctsrd/cherilibs/trunk',
                               remote               : 'svn+ssh://secsvn@svn-ctsrd.cl.cam.ac.uk/ctsrd/cherilibs/trunk'],
                              [credentialsId        : 'cffd45a1-3d92-4d8e-a485-db5a5852fe70', depthOption: 'infinity',
                               ignoreExternalsOption: true, local: 'ctsrd/cheri/trunk',
                               remote               : 'svn+ssh://secsvn@svn-ctsrd.cl.cam.ac.uk/ctsrd/cheri/trunk']],
                      workspaceUpdater: [$class: 'UpdateUpdater']])
        }
        stage(args.name + ' - Build and test') {
            doBuild(args.buildArgs, args.testArgs, args.get("assembler"))
        }
    }
}


cheriHardwareTest(
        name: "CHERI1-TEST",
        buildArgs: 'CAP=True NOPRINTS=1',
        testArgs: 'NOFUZZR=1 GENERIC_L1=1 STATCOUNTERS=1 ALLOW_UNALIGNED=1 SIM_TRACE_OPTS=')
