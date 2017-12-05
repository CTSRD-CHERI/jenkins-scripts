import com.lesfurets.jenkins.unit.global.lib.Library
@Library('jenkins-scripts') _

def stuff(Map args) {
	def commonArgs = [
			target: 'libcxxrt',
			allocateNode: false,
			skipScm: true,  // only the first run handles the SCM
			extraArgs: '--install-prefix=/']
	cheribuildProject(commonArgs + args)
}
node('linux') {
stuff(target: 'libcxxrt-baremetal', cpu: 'mips',
			needsFullCheriSDK: false, // this was already set up in the previous step from
			artifactsToCopy: [[job:'Newlib-baremetal-mips/master', filter:'newlib-baremetal-mips.tar.xz']],
			beforeBuild: 'mkdir -p cherisdk/baremetal && tar xzf newlib-baremetal-mips.tar.xz -C cherisdk/baremetal')
stuff([cpu: 'mips', skipScm: false])
stuff([target: 'libcxxrt', cpu: 'cheri128', skipScm: true, allocateNode: false])
stuff([target: 'libcxxrt', cpu: 'cheri256', skipScm: true, allocateNode: false])
stuff([target: 'libcxxrt', cpu: 'native', skipScm: true, allocateNode: false])
}