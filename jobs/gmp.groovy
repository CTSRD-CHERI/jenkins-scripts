@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'day', userBoost: true]),
        copyArtifactPermission('*'),
])

def allArchitectures = [
        'aarch64', 'amd64',
        'morello-hybrid', 'morello-purecap', 'morello-hybrid-for-purecap-rootfs',
        'riscv64', 'riscv64-hybrid', 'riscv64-purecap', 'riscv64-hybrid-for-purecap-rootfs'
]

def cmakeRepo = gitRepoWithLocalReference(url: 'https://github.com/gmp-mirror/gmp')
cheribuildProject(target: "gmp", targetArchitectures: allArchitectures,
                  scmOverride: cmakeRepo,
                  customGitCheckoutDir: 'libgmp',
                  skipArchiving: false,
                  runTests: false,
                  setGitHubStatus: false, // external repo
)
