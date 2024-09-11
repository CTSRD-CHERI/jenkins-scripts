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

def mpfrRepo = gitRepoWithLocalReference(url: 'https://gitlab.inria.fr/mpfr/mpfr.git')
def sysrootDeps = [
        [target: "gmp", job: "GMP"]
]
cheribuildProject(target: "mpfr", targetArchitectures: allArchitectures,
                  scmOverride: mpfrRepo,
                  customGitCheckoutDir: 'mpfr',
                  sysrootDependencies: sysrootDeps,
                  skipArchiving: false,
                  runTests: false,
                  setGitHubStatus: false, // external repo
)
