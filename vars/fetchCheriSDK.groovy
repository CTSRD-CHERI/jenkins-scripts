
class FetchCheriSDKArgs implements Serializable {
    String target
    String cpu = "cheri128"
    boolean compilerOnly = false
    String buildOS
    String llvmBranch = null
    String capTableABI = "pcrel"
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
        else
            params.llvmBranch = 'master'
    }
    // stage("Setup SDK for ${params.target} (${params.cpu})") {
        // now copy all the artifacts
        def llvmJob = "CLANG-LLVM-master/CPU=cheri-multi,label=${params.buildOS}"
        if (params.llvmBranch != 'master') {
            llvmJob = "CLANG-LLVM-experimental/CPU=cheri-multi,LLVM_BRANCH=${params.llvmBranch},label=${params.buildOS}"
        }
        copyArtifacts projectName: llvmJob, flatten: true, optional: false, filter: "cheri-multi-${params.llvmBranch}-clang-llvm.tar.xz", selector: lastSuccessful()
        if (!params.compilerOnly) {
            // copyArtifacts projectName: "CHERI-SDK/ALLOC=jemalloc,ISA=vanilla,SDK_CPU=${proj.sdkCPU},label=${proj.label}", filter: '*-sdk.tar.xz', fingerprintArtifacts: true
            def cheribsdProject = null
            if (params.capTableABI == "legacy") {
                cheribsdProject = "CHERIBSD-WORLD/CPU=${params.cpu},ISA=legacy"
            } else if (params.capTableABI == "pcrel") {
                cheribsdProject = "CHERIBSD-WORLD/CPU=${params.cpu},ISA=cap-table-pcrel"
            } else {
                error("Cannot infer SDK name for capTableABI=${params.capTableABI}")
            }
            copyArtifacts projectName: cheribsdProject, flatten: true, optional: false, filter: '*', selector: lastSuccessful()
            ansiColor('xterm') {
                sh "env SDK_CPU=${params.cpu} ${params.cheribuildPath}/jenkins-cheri-build.py extract-sdk --cpu ${params.cpu} ${params.extraCheribuildArgs} --cap-table-abi=${params.capTableABI}"
            }
        }
    // }
}
