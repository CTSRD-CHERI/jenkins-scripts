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

class Globals {
	static Object gitInfo = null
}

def process(String cpu, String xmlSuffix, Map args) {
	String buildDir = null
	if (cpu == "cheri128") {
		buildDir = "bodiagsuite-128-build"
	} else if (cpu == "native") {
		if (args["extraArgs"].contains('/no-use-asan')) {
			buildDir = "bodiagsuite-native-build"
		} else {
			assert(args["extraArgs"].contains('/use-asan'))
			buildDir = "bodiagsuite-asan-native-build"
		}
	} else {
		error("Invalid cpu: ${cpu}")
	}
	def commonArgs = [
			target: 'bodiagsuite', cpu: cpu, nodeLabel: null,
			skipScm: true, // only the first run handles the SCM
			skipTarball: true, runTests: true, noIncrementalBuild: true,
			afterTests: archiveTestResults(buildDir, cpu + "-" + xmlSuffix),
	]
	if (Globals.gitInfo)
		commonArgs.gitInfo = Globals.gitInfo
	if (cpu == "native") {
		// Use the native compiler instead of CHERI clang so that we can find the ASAN runtime (--without-sdk)
		commonArgs["skipArtifacts"] = true
		commonArgs["sdkCompilerOnly"] = true
		assert(args["extraArgs"].contains('--without-sdk'))
	}
	return cheribuildProject(commonArgs + args)
}
node('linux') {
	// native
	result = process('native', 'insecure',
			[stageSuffix: "Linux (insecure)", skipScm: false, // first run so we check out the scm
			 extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk'])
	Globals.gitInfo = result.gitInfo  // store it for the following runs

	process('native', 'fortify-source',
			[stageSuffix: "Linux (_FORTIFY_SOURCE)",
			 extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_FORTIFY_SOURCE=ON'])
	process('native', 'stack-protector',
			[stageSuffix: "Linux (stack-protector)",
			 extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options=-DWITH_STACK_PROTECTOR=ON'])
	process('native', 'stack-protector-and-fortify-source',
			[stageSuffix: "Linux (stack-protector+_FORTIFY_SOURCE)",
			 extraArgs: '--bodiagsuite-native/no-use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"'])
	// native+ASAN
	process('native', 'asan',
			[stageSuffix: "Linux (ASAN)",
			 extraArgs: '--bodiagsuite-native/use-asan --without-sdk'])
	process('native', 'asan-stack-protector-fortify-source',
			[stageSuffix: "Linux (ASAN+stack-protector+_FORTIFY_SOURCE)",
			 extraArgs: '--bodiagsuite-native/use-asan --without-sdk --bodiagsuite-native/cmake-options="-DWITH_FORTIFY_SOURCE=ON -DWITH_STACK_PROTECTOR=ON"'])

	// CHERI128
	process('cheri128', 'cheriabi',
			[stageSuffix: "CHERI128 (subobject default)",
			 extraArgs: ''])
	process('cheri128', 'subobject-safe',
			[stageSuffix: "CHERI128 (subobject default)",
			 extraArgs: '--subobject-bounds=subobject-safe'])
	process('cheri128', 'subobject-everywhere',
			[stageSuffix: "CHERI128 (subobject everywhere)",
			 extraArgs: '--subobject-bounds=everywhere-unsafe'])
}