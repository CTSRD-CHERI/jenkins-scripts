// import the cheribuildProject() step
@Library('ctsrd-jenkins-scripts') _

def archiveTestResults(String buildDir) {
	return {
		archiveArtifacts allowEmptyArchive: false, artifacts: "${buildDir}/test-results.xml", fingerprint: true, onlyIfSuccessful: true
		junit "${buildDir}/test-results.xml"
	}
}

cheribuildProject(target: 'bodiagsuite', cpu: 'native',
		buildStage: "Build Linux (insecure)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/no-use-asan',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterBuild: archiveTestResults("bodiagsuite-native-build"))

cheribuildProject(target: 'bodiagsuite', cpu: 'native',
		buildStage: "Build Linux (ASAN)", nodeLabel: 'linux',
		sdkCompilerOnly: true,
		extraArgs: '--bodiagsuite-native/use-asan',
		skipTarball: true, runTests: true, noIncrementalBuild: true,
		afterBuild: archiveTestResults("bodiagsuite-native-asan-build"))