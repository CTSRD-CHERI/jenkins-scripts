
class FetchCheriSDKArgs implements Serializable {
    String target
    String cpu
    boolean compilerOnly = false
    String buildOS
    String llvmBranch = null
    String cheribsdBranch = 'master'
    String capTableABI = null
    String extraCheribuildArgs = ""
    String cheribuildPath = '$WORKSPACE/cheribuild'
}

def call(Map args) {
    if (!args.get("target"))
        args["target"] = env.JOB_NAME
    if (args.get("buildOS") == null || args.get("buildOS").isEmpty())
        args["buildOS"] = inferBuildOS()

    def params = new FetchCheriSDKArgs()
    // Can't use a for loop here: https://issues.jenkins-ci.org/browse/JENKINS-49732
    args.each { it ->
        try {
            params[it.key] = it.value
        } catch (MissingPropertyException e) {
            error("fetchCheriSDK: Unknown argument ${it.key}: ${e}")
            return params
        } catch (IllegalArgumentException e) {
            error("fetchCheriSDK: Bad value ${it.value} for argument ${it.key}: ${e.getMessage()}")
            return params
        } catch (e) {
            error("fetchCheriSDK: Could not set argument ${it.key} to ${it.value}: ${e}")
            return params
        }
    }
    if (params.cpu == null) {
        error("fetchCheriSDK: Missing 'cpu' parameter")
    }


    // Infer the correct LLVM for this project (dev/devel builds with LLVM dev)
    if (!params.llvmBranch) {
        def gitBranch = 'master'
        if (env.CHANGE_ID) {
            gitBranch = env.CHANGE_TARGET
        } else if (env.BRANCH_NAME) {
            gitBranch = env.BRANCH_NAME
        }
        if (gitBranch == 'dev' || gitBranch == 'devel')
            params.llvmBranch = 'dev'
        else if (gitBranch == 'faster-testsuite-runs' || gitBranch == 'cheri-purecap-kernel')
            params.llvmBranch = 'dev' // FIXME: remove when LLVM dev->master merge complete
        else if (gitBranch == 'abi-breaking-changes')
            params.llvmBranch = 'abi-breaking-changes'
        else if (gitBranch == 'upstream-llvm-merge')
            params.llvmBranch = 'upstream-llvm-merge'
        else
            params.llvmBranch = 'master'
        // echo("Inferred LLVM branch from current git branch (${gitBranch}): ${params.llvmBranch}")
    }
    // stage("Setup SDK for ${params.target} (${params.cpu})") {
        // now copy all the artifacts
        def llvmJob = "CLANG-LLVM-${params.buildOS}/${params.llvmBranch}"
        String llvmArtifact = 'cheri-clang-llvm.tar.xz'
        String compilerType = 'cheri-llvm'
        if (params.cpu.startsWith("morello")) {
            // Note: Morello LLVM has a morello/master and a morello/dev branch, so we just prefix llvmBranch with morello/
            llvmJob = 'Morello-LLVM-linux/morello/${params.llvmBranch}'
            llvmArtifact = 'morello-clang-llvm.tar.xz'
            compilerType = 'morello-llvm'
        }
        copyArtifacts projectName: llvmJob, flatten: true, optional: false, filter: llvmArtifact, selector: lastSuccessful()
        // Rename the archive to the expected name
        // FIXME: add cheribuild argument to allow overriding this
        def extraArgs = ["--compiler-archive=${llvmArtifact}", "--compiler-type=${compilerType}"]
        if (params.compilerOnly || params.cpu == 'native') {
            extraArgs += ['--extract-compiler-only']
        } else {
            // FIXME: needs to be updated to use the new job names
            def cheribsdProject = null
            def sysrootArchive = null
            if (!params.capTableABI || params.capTableABI == "pcrel") {
                cheribsdProject = "CheriBSD-pipeline/${params.cheribsdBranch}"
                sysrootArchive = "artifacts-${params.cpu}/cheribsd-sysroot.tar.xz"
            } else {
                error("Cannot infer SDK name for capTableABI=${params.capTableABI}")
            }
            extraArgs += ["--sysroot-archive=${sysrootArchive}"]
            copyArtifacts projectName: cheribsdProject, flatten: false, optional: false, filter: sysrootArchive, selector: lastSuccessful()
        }
        ansiColor('xterm') {
            sh label: 'extracting SDK archive:', script: """
# delete old SDK first and then use cheribuild to extract the new one
rm -rf cherisdk/ morello-sdk/ native-sdk/ upstream-llvm-sdk/
${params.cheribuildPath}/jenkins-cheri-build.py extract-sdk ${extraArgs.join(" ")}"""
        }
    // }
}
