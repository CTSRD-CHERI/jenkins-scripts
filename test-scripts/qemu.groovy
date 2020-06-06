// import the cheribuildProject() step
@Library('ctsrd-jenkins-scripts') _

properties([
		disableConcurrentBuilds(),
		disableResume(),
		[$class: 'GithubProjectProperty', projectUrlStr: 'https://github.com/CTSRD-CHERI/qemu'],
		copyArtifactPermission('*'),
		durabilityHint('PERFORMANCE_OPTIMIZED'),
		pipelineTriggers([githubPush(), pollSCM('@daily')])
])

def archiveQEMU(String target) {
	return {
		sh "rm -rf \$WORKSPACE/qemu-${target} && mv \$WORKSPACE/tarball/usr \$WORKSPACE/qemu-${target}"
		archiveArtifacts allowEmptyArchive: false, artifacts: "qemu-${target}/bin/qemu-system-*, qemu-${target}/share/qemu/efi-pcnet.rom, qemu-${target}/share/qemu/vgabios-cirrus.bin", fingerprint: true, onlyIfSuccessful: true
	}
}

def archiveBBL(List targets) {
	sh "find \$WORKSPACE/tarball/"
	// archiveArtifacts allowEmptyArchive: false, artifacts: "qemu-${target}/bin/qemu-system-*, qemu-${target}/share/qemu/efi-pcnet.rom, qemu-${target}/share/qemu/vgabios-cirrus.bin", fingerprint: true, onlyIfSuccessful: true
}

bblRepo = gitRepoWithLocalReference(url: 'https://github.com/CTSRD-CHERI/riscv-pk.git')
bblRepo["branches"] = [[name: '*/without-sysroot']]
echo("${bblRepo}")
cheribuildProject(target: 'bbl-baremetal-riscv64-purecap', skipArtifacts: true,
		buildStage: "Build BBL BIOS", nodeLabel: 'linux',
		extraArgs: '--install-prefix=/', scmOverride: bblRepo,
		sdkCompilerOnly: true, needsFullCheriSDK: false, skipTarball: true,
		afterBuild: { archiveBBL(['freebsd', 'linux']) }
)

cheribuildProject(target: 'qemu', cpu: 'native', skipArtifacts: true,
		buildStage: "Build Linux",
		nodeLabel: 'xenial', // build on the oldest supported ubuntu version so the binaries also run there
		extraArgs: '--without-sdk --install-prefix=/usr',
		runTests: true,
		skipTarball: true, afterBuild: archiveQEMU('linux'))

cheribuildProject(target: 'qemu', cpu: 'native', skipArtifacts: true,
		buildStage: "Build FreeBSD", nodeLabel: 'freebsd',
		extraArgs: '--without-sdk --install-prefix=/usr',
		runTests: true,
		skipTarball: true, afterBuild: archiveQEMU('freebsd'))
