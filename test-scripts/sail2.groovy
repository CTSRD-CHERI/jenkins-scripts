import groovy.json.JsonBuilder

@Library('ctsrd-jenkins-scripts') _

class JobConfig {
    String testTarget
    String testArgs
    String assembler = 'clang'
    String sailModel = null
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
    boolean usesBinutils = false;
    if (jobName.endsWith('-gnu-binutils')) {
        usesBinutils = true
        jobName = jobName.substring(0, jobName.indexOf('-gnu-binutils'))
    }
    echo "Computed base job names as $jobName"
    Map config = ["sail2-CHERI256": [testTarget: 'nosetests_sail_cheri',
                                     sailModel: 'cheri',
                                     testArgs : 'TEST_CP2=1 CAP_SIZE=256'],
                  "sail2-CHERI128": [testTarget: 'nosetests_sail_cheri128',
                                     sailModel: 'cheri',
                                     testArgs : 'TEST_CP2=1 CAP_SIZE=128'],
                  "sail2-MIPS"    : [testTarget: 'nosetests_sail',
                                     sailModel: 'mips',
                                     testArgs : 'TEST_CP2=0 CAP_SIZE=256'],]
    Map result = config.get(jobName)
    if (!result) {
        error("No configuration found for job ${jobName}! Please add one to the Map above")
    }
    if (usesBinutils)
        result.assembler = 'gnu-binutils'
    result.name = jobName
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
    // Don't test the FPU
    String commonTestFlag = "SAIL_DIR=\${WORKSPACE}/sail TEST_FPU=0 ${assemblerTestFlag}"
    stage("Build ELFs (${args.assembler})") {
        sh """#!/bin/bash
set -xe
cd \$WORKSPACE
${prepareAssembler}

cd cheritest
# always do a full clean in case the linker/compiler has changed
make clean
make elfs ${commonTestFlag} ${args.testArgs} elfs -j16
"""
    }
    stage("Run tests (${args.assembler})") {
        dir ("cheritest") {
            sh """
rm -f nose*.xml pytest*.xml
ulimit -f 10485760
ulimit -c 0
make ${commonTestFlag} ${args.testArgs} ${args.testTarget} -j16
"""
        }
        // JUnit Results
        junit 'cheritest/nosetests_*.xml'
    }
}

def doBuild(JobConfig args) {
    if (args.name.startsWith('BERI')) {
        if (!args.testArgs.contains('TEST_CP2=0')) {
            error("BERI tests need TEST_CP2=0 set")
            return
        }
    }
    timeout(240) {
        copyArtifacts filter: args.binutilsArchiveName, fingerprintArtifacts: true, projectName: args.binutilsJobName
        copyArtifacts filter: args.clangArchiveName, fingerprintArtifacts: true, projectName: args.clangJobName
        stage('Build sail') {
            env.MODEL = args.sailModel
            sh '''
eval `opam config env`
opam list
ulimit -s unlimited

tar xjf binutils.tar.bz2
tar xJf cheri-multi-master-clang-llvm.tar.xz
export PATH=${WORKSPACE}/binutils/bin:${WORKSPACE}/ott/bin:${WORKSPACE}/lem/bin:${PATH}
export OCAMLPATH=${WORKSPACE}/lem/ocaml-lib/local:${WORKSPACE}/linksem/src/local
export LEMLIB=${WORKSPACE}/lem/library

cd ${WORKSPACE}/ott
make

cd ${WORKSPACE}/lem
make
make -C ocaml-lib local-install

cd ${WORKSPACE}/linksem
make USE_OCAMLBUILD=false
make -C src USE_OCAMLBUILD=false local-install

cd ${WORKSPACE}/sail
make

case $MODEL in
mips) cd mips ;;
cheri) cd cheri ;;
cheri128) cd cheri ;;
esac

make $MODEL
'''
            // archiveArtifacts allowEmptyArchive: false, artifacts: 'ctsrd/cheri/trunk/sim, ctsrd/cheri/trunk/sim.so, ctsrd/cheri/trunk/sim.dtb, ctsrd/cheri/trunk/build_*_sim/sim, ctsrd/cheri/trunk/build_*_sim/sim.so, ctsrd/cheri/trunk/build_*_sim/sim.dtb, ctsrd/cherilibs/trunk/peripherals/*.so, ctsrd/cherilibs/trunk/tools/memConv.py', caseSensitive: true, defaultExcludes: true, excludes: 'ctsrd/cheritest/**/*', fingerprint: false, onlyIfSuccessful: true
        }
        timeout(90) {
            runTests(args)
        }
        warnings canComputeNew: false, canResolveRelativePaths: false, consoleParsers: [[parserName: 'Clang (LLVM based)']]
        step([$class: 'AnalysisPublisher', canComputeNew: false])
    }
}

def cheriHardwareTest() {
    node('xenial') {
        deleteDir() // clean workspace
        echo "Computing job parameters for ${env.JOB_NAME}"
        JobConfig args = getJobParameters()
        argsStr = new JsonBuilder(args).toPrettyString()
        echo "Found job config: BUILD_ARGS: '${argsStr}'"
        stage('Checkout') {
            dir('cheritest') {
                args.gitInfo = checkout scm  // get the sources from git
            }
            // Get the other sources:
            dir('sail') {
                git branch: 'sail2', url: 'https://github.com/rems-project/sail', changelog: true, poll: true
            }
            dir('lem') {
                git branch: 'master', url: 'https://github.com/rems-project/lem', changelog: true, poll: false
            }
            dir('linksem') {
                git branch: 'master', url: 'https://github.com/rems-project/linksem', changelog: true, poll: false
            }
            dir('ott') {
                git branch: 'master', url: 'https://github.com/ott-lang/ott', changelog: true, poll: false
            }
        }
        try {
            setGitHubStatus(args.gitInfo) // set PENDING status
            doBuild(args)
        } finally {
            echo("result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
            // it seems like the currentBuild.Result will always be null (PENDING) unless I set it explicitly here
            if (currentBuild.result == null)
                currentBuild.result = currentBuild.currentResult
            echo("final result = ${currentBuild.result} currentResult = ${currentBuild.currentResult}")
            setGitHubStatus(args.gitInfo)
        }
    }
}

try {
    properties([pipelineTriggers([[$class: "GitHubPushTrigger"]]),
                disableConcurrentBuilds(),
                [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],])
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
