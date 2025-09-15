// import the cheribuildProject() step
@Library('ctsrd-jenkins-scripts') _

def archiveTestResults(String buildDir, String testSuffix) {
    return {
        String outputXml = "test-results-${testSuffix}.xml"
        sh """
rm -f ${outputXml}
ls -la ${buildDir}/test-results.xml
mv -f ${buildDir}/test-results.xml ${outputXml}
"""
        archiveArtifacts allowEmptyArchive: false, artifacts: "${outputXml}", fingerprint: true, onlyIfSuccessful: false
        // Only record junit results for cases where almost all tests should pass (ignore insecure native runs)
        if (buildDir.contains("-asan-") || buildDir.contains("-128-")) {
            junit "${outputXml}"
        }
        // Cleanup after archiving the test results
        dir("${buildDir}") { deleteDir() }
    }
}

def process(String cpu, String xmlSuffix, Map args) {
    String buildDir = null
    if (cpu == "cheri128") {
        buildDir = "bodiagsuite-128-build"
    } else if (cpu == "native" || cpu == "mips") {
        if (args["extraArgs"].contains('/no-use-asan')) {
            buildDir = "bodiagsuite-${cpu}-build"
        } else {
            assert (args["extraArgs"].contains('/use-asan'))
            buildDir = "bodiagsuite-asan-${cpu}-build"
        }
    } else {
        error("Invalid cpu: ${cpu}")
    }
    def commonArgs = [target     : 'bodiagsuite', cpu: cpu,
                      skipScm    : false, nodeLabel: "linux",
                      skipTarball: true, runTests: true,
                      afterTests : archiveTestResults(buildDir, cpu + "-" + xmlSuffix),
                      beforeSCM: { sh 'rm -rf bodiagsuite-*-build *.xml' } ]
    if (cpu == "native") {
        // Use the native compiler instead of CHERI clang so that we can find the ASAN runtime (--without-sdk)
        commonArgs["skipArtifacts"] = true
        commonArgs["sdkCompilerOnly"] = true
        assert (args["extraArgs"].contains('--without-sdk'))
    }
    echo("args = ${commonArgs + args}")
    return cheribuildProject(commonArgs + args)
}

def jobs = [
// native
"Linux (insecure)"               : {
    process('native', 'insecure',
            [stageSuffix: "Linux (insecure)", extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk'])
},
"Linux (fortify)"                : {
    process('native', 'fortify-source',
            [stageSuffix: "Linux (_FORTIFY_SOURCE)",
             extraArgs  : '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_FORTIFY_SOURCE=ON'])
},
"Linux (stack-protector)"        : {
    process('native', 'stack-protector',
            [stageSuffix: "Linux (stack-protector)",
             extraArgs  : '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_STACK_PROTECTOR=ON'])
},
"Linux (stack-protector+fortify)": {
    process('native', 'stack-protector-and-fortify-source',
            [stageSuffix: "Linux (stack-protector+_FORTIFY_SOURCE)",
             extraArgs  : '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"'])
},


// native+ASAN
"Linux (ASAN)"                   : {
    process('native', 'asan',
            [stageSuffix: "Linux (ASAN)",
             extraArgs  : '--bodiagsuite-native/use-asan --without-sdk'])
},
"Linux (ASAN+sp+fortify)"        : {
    process('native', 'asan-stack-protector-fortify-source',
            [stageSuffix: "Linux (ASAN+stack-protector+_FORTIFY_SOURCE)",
             extraArgs  : '--bodiagsuite-native/use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"'])
},


// MIPS:
"FreeBSD MIPS (insecure)"        : {
    process('mips', 'insecure',
            [stageSuffix: "FreeBSD MIPS (insecure)",
             extraArgs  : '--bodiagsuite-mips/no-use-asan'])
},
"FreeBSD MIPS (ASAN)"            : {
    process('mips', 'asan',
            [stageSuffix: "FreeBSD MIPS (ASAN)",
             extraArgs  : '--bodiagsuite-mips/use-asan'])
},


// CHERI128
"CheriABI"                       : {
    process('cheri128', 'cheriabi',
            [stageSuffix: "CHERI128",
             extraArgs  : ''])
},
"CheriABI+subobject-safe"        : {
    process('cheri128', 'subobject-safe',
            [stageSuffix: "CHERI128 (subobject default)",
             extraArgs  : '--subobject-bounds=subobject-safe'])
},
"CheriABI+subobject-everywhere"  : {
    process('cheri128', 'subobject-everywhere',
            [stageSuffix: "CHERI128 (subobject everywhere)",
             extraArgs  : '--subobject-bounds=everywhere-unsafe'])
}
]
// print(jobs)
jobs.failFast = true
parallel jobs
