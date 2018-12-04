@Library('ctsrd-jenkins-scripts') _


node("linux") {
    stage("Checkout") {
        // Fetch the SVN repo first
        checkout([$class: 'SubversionSCM',
                  filterChangelog: false, ignoreDirPropChanges: false,
                  locations: [
                          [cancelProcessOnExternalsFail: true, credentialsId: 'cffd45a1-3d92-4d8e-a485-db5a5852fe70', depthOption: 'infinity',
                           ignoreExternalsOption: true, local: 'charon2', remote: 'svn+ssh://secsvn@svn-rsem007.cl.cam.ac.uk/csem/charon2']
                  ],
                  quietOperation: true, workspaceUpdater: [$class: 'UpdateUpdater']]
        )
        dir('cheribuild') {
            cloneGitRepoWithReference(url: "https://github.com/CTSRD-CHERI/cheribuild.git", changelog: false, poll: false)
        }
    }
    stage("copy artifacts") {
        fetchCheriSDK(cpu: "cheri128", capTableABI: "pcrel")
        sh 'rm -rfv $WORKSPACE/*kernel.bz2 qemu-linux'
        copyArtifacts projectName: "qemu/qemu-cheri", filter: "qemu-linux/**", target: '.', fingerprintArtifacts: false, selector: lastSuccessful()
        // There is no need for the binaries to be CHERIABI
        // https://ctsrd-build.cl.cam.ac.uk/job/CheriBSD-allkernels-multi/BASE_ABI=n64,CPU=cheri128,ISA=vanilla,label=freebsd/lastSuccessfulBuild/artifact/
        // ctsrd/cheribsd/trunk/bsdtools/cheribsd128-cheri128-malta64-mfs-root-minimal-cheribuild-kernel.bz2
        def diskImageProjectName = 'CheriBSD-allkernels-multi/BASE_ABI=n64,CPU=cheri128,ISA=vanilla,label=freebsd'
        def diskImagePath = 'ctsrd/cheribsd/trunk/bsdtools/cheribsd128-cheri128-malta64-mfs-root-minimal-cheribuild-kernel.bz2'
        copyArtifacts projectName: diskImageProjectName, filter: diskImagePath, target: '.', fingerprintArtifacts: false, flatten: true
    }
    stage("build") {
    }
    stage("run") {
    }
}