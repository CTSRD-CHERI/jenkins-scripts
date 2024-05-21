@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'day', userBoost: true]),
        copyArtifactPermission('*'),
])

def cmakeRepo = gitRepoWithLocalReference(url: 'https://gitlab.kitware.com/cmake/cmake.git', branch: 'release')
cheribuildProject(target: 'cmake',
                  targetArchitectures: ["native", "aarch64", "amd64", "morello-purecap", "riscv64", "riscv64-purecap"],
                  scmOverride: cmakeRepo,
                  skipArchiving: false,
                  runTests: false,
                  setGitHubStatus: false, // external repo
)
