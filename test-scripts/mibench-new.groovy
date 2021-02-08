// Import the cheribuildProject/gitRepoWithLocalReference build steps
@Library('ctsrd-jenkins-scripts') _

// We use the LLVM test suite repository to get the MiBench sources
mibenchRepo = gitRepoWithLocalReference(url: 'https://github.com/CTSRD-CHERI/llvm-test-suite.git')
mibenchRepo["branches"] = [[name: '*/master']]
cheribuildProject(target: 'mibench-new',
        targetArchitectures: ["mips64", "mips64-purecap", "riscv64", "riscv64-purecap"],
        customGitCheckoutDir: 'llvm-test-suite', scmOverride: mibenchRepo,
        nodeLabel: 'linux', buildStage: "Build MiBench",
        extraArgs: '--install-prefix=/')
