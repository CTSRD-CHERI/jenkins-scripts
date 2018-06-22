@Library('ctsrd-jenkins-scripts') _

class JobConfig {
    String testArgs
    String qemuSuffix
    String assembler = 'clang'
    String name = null
    static Object gitInfo = null
    static Object QEMUgitInfo = null

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
    if (jobName.endsWith('-TEST-pipeline')) {
        jobName = jobName.substring(0, jobName.indexOf('-TEST-pipeline'))
    }
    boolean usesBinutils = false;
    if (jobName.endsWith('-gnu-binutils')) {
        usesBinutils = true
        jobName = jobName.substring(0, jobName.indexOf('-gnu-binutils'))
    }
    echo "Computed base job names as $jobName"
    Map config = [
            "QEMU-CHERI256": [testArgs: 'CAP_SIZE=256 CAP_PRECISE=1 TEST_CP2=1',
                              qemuSuffix: 'cheri256'],
            "QEMU-CHERI128": [testArgs: 'CAP_SIZE=128 CAP_PRECISE=0 TEST_CP2=1',
                              qemuSuffix: 'cheri128'],
            "QEMU-CHERI128-MAGIC": [testArgs: 'CAP_SIZE=128 CAP_PRECISE=1 TEST_CP2=1',
                                    qemuSuffix: 'cheri128magic'],
            "QEMU-MIPS64": [testArgs: 'CAP_SIZE=0 CAP_PRECISE=0 PERM_SIZE=0 TEST_CP2=0',
                            qemuSuffix: 'mips64'],
    ]
    Map result = config.get(jobName)
    if (!result) {
        error("No configuration found for job ${jobName}! Please add one to the Map above")
    }
    result.name = jobName
    if (usesBinutils)
        result.assembler = 'gnu-binutils'
    return result as JobConfig
}


def runTests(JobConfig args) {
    if (args.assembler == 'gnu-binutils') {
        copyArtifacts filter: args.binutilsArchiveName, fingerprintArtifacts: true, projectName: args.binutilsJobName
    } else {
        if (args.assembler != 'clang') {
            error("Bad compiler: ${args.compiler}")
        }
        copyArtifacts filter: args.clangArchiveName, fingerprintArtifacts: true, projectName: args.clangJobName
    }
    def prepareAssembler = ''
    def assemblerTestFlag = ''
    if (args.assembler == 'clang') {
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
    String qemuFullpath = "\$WORKSPACE/qemu-linux/bin/qemu-system-${args.qemuSuffix}"
    // Also test the FPU
    String commonTestFlag = "TEST_FPU=1 ${assemblerTestFlag} QEMU=${qemuFullpath}"
    stage("Build ELFs (${args.assembler})") {
        sh """#!/bin/bash
set -xe
cd \$WORKSPACE
${prepareAssembler}

cd \$WORKSPACE/ctsrd/cheritest/trunk
# always do a full clean in case the linker/compiler has changed
make distclean
make elfs ${commonTestFlag} ${args.testArgs} elfs -j16
"""
    }
    stage("Run tests (${args.assembler})") {
        sh """
${qemuFullpath} --version
echo "QEMU git hash:"
${qemuFullpath} --version | grep version | perl -pe 's/.*\\(.*\\-g(\\w+)\\).*/\$1/'
"""
        dir ("ctsrd/cheritest/trunk") {
            sh """
rm -f nose*.xml pytest*.xml
ulimit -f 10485760
ulimit -c 0
make PYTHON_TESTRUNNER=pytest ${commonTestFlag} ${args.testArgs} nosetests_qemu -j16
"""
        }
        // JUnit Results
        junit 'ctsrd/cheritest/trunk/nosetests_*.xml'
    }
}

def doBuild(JobConfig args) {
    def qemuGitOptions = [ changelog: true, poll: true,
                    scm: [$class: 'GitSCM',
                          doGenerateSubmoduleConfigurations: false,
                          branches: [[name: "refs/heads/qemu-cheri"]],
                          extensions: [/* to skip polling: [$class: 'IgnoreNotifyCommit'], */
                                       [$class: 'CloneOption', noTags: false, reference: '/var/tmp/git-reference-repos/qemu', shallow: false, depth: 10, timeout: 60],
                          ],
                          userRemoteConfigs: [[url: 'https://github.com/CTSRD-CHERI/qemu.git', credentialsId: 'ctsrd-jenkins-api-token-with-username']]
                    ]
    ]
    JobConfig.QEMUgitInfo = [:]
    def proj = cheribuildProject(target: 'qemu', cpu: 'native', skipArtifacts: true, scmOverride: qemuGitOptions,
                    buildStage: "Build QEMU with coverage", nodeLabel: null, noIncrementalBuild: true,
                    gitInfoMap: JobConfig.QEMUgitInfo, // will be updated by the project
                    extraArgs: '--unified-sdk --without-sdk --install-prefix=/ --qemu/no-use-lto --qemu/debug-info --qemu/configure-options=--enable-gcov --output-path=qemu-linux',
                    skipTarball: true, setGitHubStatus: false // This is done manually later
                )
    if (!JobConfig.QEMUgitInfo || JobConfig.QEMUgitInfo.isEmpty())
        JobConfig.QEMUgitInfo = proj.gitInfo  // save the qemu git info for setting commit status
    echo("QEMU git info: ${JobConfig.QEMUgitInfo}")
    timeout(90) {
        runTests(args)
    }
}

def cheriHardwareTest() {
    node('linux') {
        deleteDir()
        echo "Computing job parameters for ${env.JOB_NAME}"
        JobConfig args = getJobParameters()
        echo "Found job config: QEMU: '${args.qemuSuffix}'\nTEST_ARGS: '${args.testArgs}'"
        stage('Checkout') {
            // dir('ctsrd/cheritest/trunk') { git url: 'git@github.com:CTSRD-CHERI/cheritest.git', credentialsId: 'cheritest_key', branch: 'master'}
            dir('ctsrd/cheritest/trunk') {
                args.gitInfo = checkout scm  // get the sources from git
            }
        }
        try {
            setGitHubStatus(args.gitInfo) // set PENDING status
            doBuild(args)
        } catch (e) {
            currentBuild.result = 'FAILURE'
            error("Failure detected: ${e}")
        } finally {
            echo("result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
            // it seems like the currentBuild.Result will always be null (PENDING) unless I set it explicitly here
            if (currentBuild.result == null)
                currentBuild.result = currentBuild.currentResult
            echo("final result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
            setGitHubStatus(args.gitInfo)
            // Also set the status on the QEMU repo
            setGitHubStatus(args.QEMUgitInfo)
        }
    }
}

try {
    properties([
            pipelineTriggers([
                    [$class: "GitHubPushTrigger"],
                    pollSCM('@daily')
            ]),
            disableConcurrentBuilds(),
            [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
    ])
    cheriHardwareTest()
} catch (e) {
    error(e.toString())
    emailext body: '$DEFAULT_CONTENT', recipientProviders: [culprits(), brokenBuildSuspects(), brokenTestsSuspects(), requestor()], subject: '$DEFAULT_SUBJECT'
} finally {
}
