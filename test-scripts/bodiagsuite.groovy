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
		archiveArtifacts allowEmptyArchive: false, artifacts: "${outputXml}", fingerprint: true, onlyIfSuccessful: true
		junit "${outputXml}"
		// Cleanup after archiving the test results
		dir ("${buildDir}") { deleteDir() }
	}
}

// Use the native compiler instead of CHERI clang so that we can find the ASAN runtime
cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (insecure)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-native-build", "native-insecure"))

cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (_FORTIFY_SOURCE)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_FORTIFY_SOURCE=ON',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-native-build", "native-fortify"))

cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (stack-protector)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_STACK_PROTECTOR=ON',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-native-build", "native-stack-protector"))

cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (stack-protector+_FORTIFY_SOURCE)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-native-build", "native-stack-protector-fortify"))

// native + ASAN
cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (ASAN)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/use-asan --without-sdk',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-asan-native-build", "native-asan"))

cheribuildProject(target: 'bodiagsuite', cpu: 'native', skipArtifacts: true,
		stageSuffix: "Linux (ASAN+stack-protector+_FORTIFY_SOURCE)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-asan-native-build", "native-asan-stack-protector-fortify"))

// CHERI128:

cheribuildProject(target: 'bodiagsuite', cpu: 'cheri128',
		stageSuffix: "CHERI128", nodeLabel: 'linux',
		extraArgs: '',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-128-build", "cheri128"))

cheribuildProject(target: 'bodiagsuite', cpu: 'cheri128',
		stageSuffix: "CHERI128 (subobject default)", nodeLabel: 'linux',
		extraArgs: '--subobject-bounds=subobject-safe',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-128-build", "cheri128-subobject-safe"))

cheribuildProject(target: 'bodiagsuite', cpu: 'cheri128',
		stageSuffix: "CHERI128 (subobject everywhere)", nodeLabel: 'linux',
		extraArgs: '--subobject-bounds=everywhere-unsafe',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterTests: archiveTestResults("bodiagsuite-128-build", "cheri128-subobject-aggressive"))
