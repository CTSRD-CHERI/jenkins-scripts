@Library('ctsrd-jenkins-scripts') _

properties([disableConcurrentBuilds(),
            // compressBuildLog(), // Broken, see https://issues.jenkins-ci.org/browse/JENKINS-54680
            disableResume(),
            [$class: 'GithubProjectProperty', displayName: '', projectUrlStr: 'https://github.com/CTSRD-CHERI/llvm-project/'],
            [$class: 'CopyArtifactPermissionProperty', projectNames: '*'],
            [$class: 'JobPropertyImpl', throttle: [count: 2, durationName: 'hour', userBoost: true]],
            durabilityHint('PERFORMANCE_OPTIMIZED'),
            pipelineTriggers([githubPush()])
])

if (TEST_RELEASE_BUILD) {
    buildScript += '''CMAKE_ARGS+=("-DCMAKE_BUILD_TYPE=Release" "-DLLVM_ENABLE_ASSERTIONS=OFF" "-DBUILD_SHARED_LIBS=OFF" "-DLLVM_ENABLE_EXPENSIVE_CHECKS=OFF")'''
} else {
    // Release build with assertions is a bit faster than a debug build and A LOT smaller
    buildScript += '''
CMAKE_ARGS+=("-DCMAKE_BUILD_TYPE=Release" "-DLLVM_ENABLE_ASSERTIONS=ON")
'''
}
if (TEST_WITH_SANITIZERS) {
    individualTestTimeout = 600  // Sanitizer builds are slow
    buildScript += '''
CMAKE_ARGS+=("-DLLVM_USE_SANITIZER=Address;Undefined")
'''
}

def nodeLabel = null
if (env.JOB_NAME.toLowerCase().contains("linux")) {
    nodeLabel = "linux"
} else if (env.JOB_NAME.toLowerCase().contains("freebsd")) {
    nodeLabel = "freebsd"
} else {
    error("Invalid job name: ${env.JOB_NAME}")
}

node(nodeLabel) {
    try {
        env.label = nodeLabel
        env.SDKROOT_DIR = "${env.WORKSPACE}/sdk"
        cheribuildProject(target: 'llvm-native',
                nodeLabel: nodeLabel,
                extraArgs: '--without-sdk --install-prefix=/usr',
                runTests: true,
                skipArchiving: skipArchiving,
                skipTarball: skipTarball, afterBuild: archiveQEMU('linux'))
    } finally {
        // Remove the test binaries to save some disk space and to make typos in
        // test scripts fail the build even if a previous commit created that file
        for (path in [env.SDKROOT_DIR, 'llvm-build/test',
                     'llvm-build/tools/clang/test', 'llvm-build/tools/lld/test']) {
            dir(path) {
                deleteDir()
            }
        }
    }
}
