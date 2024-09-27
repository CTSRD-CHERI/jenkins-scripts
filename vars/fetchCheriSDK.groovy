
class FetchCheriSDKArgs implements Serializable {
    String target
    String cpu
    boolean compilerOnly = false
    String buildOS
    String llvmBranch = null
    String morelloLlvmBranch = null
    String cheribsdBranch = 'main'
    String capTableABI = null
    String extraCheribuildArgs = ""
    String cheribuildPath = '$WORKSPACE/cheribuild'
    List sysrootExtraArchives = []
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
    def gitBranch = 'master'
    if (env.CHANGE_ID) {
        gitBranch = env.CHANGE_TARGET
    } else if (env.BRANCH_NAME) {
        gitBranch = env.BRANCH_NAME
    }
    if (!params.llvmBranch) {
        if (gitBranch in ['c18n', 'caprevoke', 'cocall', 'cocalls', 'coexecve',
                          'dev', 'devel', 'demo-2024-03', 'kernel-c18n', 'demo-2024-10'])
            params.llvmBranch = 'dev'
        else if (gitBranch == 'abi-breaking-changes')
            params.llvmBranch = 'abi-breaking-changes'
        else if (gitBranch == 'upstream-llvm-merge')
            params.llvmBranch = 'upstream-llvm-merge'
        else
            params.llvmBranch = 'master'
        // echo("Inferred LLVM branch from current git branch (${gitBranch}): ${params.llvmBranch}")
    }
    if (!params.morelloLlvmBranch) {
        // Note: Morello LLVM has a morello/master and a morello/dev branch, so we just prefix llvmBranch with
        // morello%2F (Jenkins URL-encodes the branch name)
        if (gitBranch == 'abi-breaking-changes' || gitBranch == 'upstream-llvm-merge')
            params.morelloLlvmBranch = 'morello%2Fdev'
        else if (gitBranch == 'demo-2024-06')
            params.morelloLlvmBranch = 'elf_sig'
        else if (gitBranch == 'kernel-c18n')
            params.morelloLlvmBranch = 'kernel-c18n'
        else
            params.morelloLlvmBranch = "morello%2F${params.llvmBranch}"
    }
    // stage("Setup SDK for ${params.target} (${params.cpu})") {
    if (true) {
        // now copy all the artifacts
        def llvmJob = "CLANG-LLVM-${params.buildOS}/${params.llvmBranch}"
        String llvmArtifact = 'cheri-clang-llvm.tar.xz'
        String compilerType = 'cheri-llvm'
        if (params.cpu.startsWith("morello")) {
            llvmJob = "Morello-LLVM-linux/${params.morelloLlvmBranch}"
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
            if (!params.sysrootExtraArchives.isEmpty()) {
                extraArgs += ["--sysroot-extra-archives='${params.sysrootExtraArchives.join(" ")}'"]
            }
            copyArtifacts projectName: cheribsdProject, flatten: false, optional: false, filter: sysrootArchive, selector: lastSuccessful()
        }
        ansiColor('xterm') {
            sh label: 'extracting SDK archive:', script: """
# delete old SDK first and then use cheribuild to extract the new one
rm -rf cherisdk/ morello-sdk/ native-sdk/ upstream-llvm-sdk/
${params.cheribuildPath}/jenkins-cheri-build.py --extract-sdk ${extraArgs.join(" ")} ${params.target}"""
        }
    }
}
