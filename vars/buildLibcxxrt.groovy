def process(Map args) {
	def commonArgs = [
			target: 'libcxxrt',
			allocateNode: false,
			skipScm: true,  // only the first run handles the SCM
			extraArgs: '--install-prefix=/']
	cheribuildProject(commonArgs + [cpu: 'mips', skipScm: false])
}
node('linux') {
	process(target: 'libcxxrt-baremetal', cpu: 'mips',
			needsFullCheriSDK: false, // this was already set up in the previous step from
			artifactsToCopy: [[job:'Newlib-baremetal-mips/master', filter:'newlib-baremetal-mips.tar.xz']],
			beforeBuild: 'mkdir -p cherisdk/baremetal && tar xzf newlib-baremetal-mips.tar.xz -C cherisdk/baremetal')
	process([cpu: 'mips', skipScm: false])
	process([target: 'libcxxrt', cpu: 'cheri128', skipScm: true, allocateNode: false])
	process([target: 'libcxxrt', cpu: 'cheri256', skipScm: true, allocateNode: false])
	process([target: 'libcxxrt', cpu: 'native', skipScm: true, allocateNode: false])
}