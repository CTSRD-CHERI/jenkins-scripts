
class FetchCheriSDKArgs implements Serializable {
    String target
    String cpu = "cheri128"
    boolean compilerOnly = false
    String buildOS
    String capTableABI = "pcrel"
    String extraCheribuildArgs = ""
    String cheribuildPath = '$WORKSPACE/cheribuild'
}

def inferBuildOS() {
    def labels = "${env.NODE_LABELS}"
    if (labels.contains("linux"))
        return "linux"
    if (labels.contains("freebsd"))
        return "freebsd"
    error("Could not determine node label from '${env.NODE_LABELS}'")
}

def call(Map args) {
    if (!args.get("target"))
        args["target"] = env.JOB_NAME
    if (!args.get("buildOS"))
        args["buildOS"] = inferBuildOS()

    def params = new FetchCheriSDKArgs()
    for (it in args) {
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

    stage("Setup SDK for ${params.target} (${params.cpu})") {
        // now copy all the artifacts
        copyArtifacts projectName: "CLANG-LLVM-master/CPU=cheri-multi,label=${params.buildOS}", flatten: true, optional: false, filter: 'cheri-multi-master-clang-llvm.tar.xz', selector: lastSuccessful()
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
                sh "${params.cheribuildPath}/jenkins-cheri-build.py extract-sdk --cpu ${params.cpu} ${params.extraCheribuildArgs} --cap-table-abi=${params.capTableABI}"
            }
        }
    }
}
