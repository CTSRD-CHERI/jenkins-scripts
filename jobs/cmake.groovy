@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'day', userBoost: true]),
        copyArtifactPermission('*'),
])

def cmakeRepo = gitRepoWithLocalReference(url: 'https://gitlab.kitware.com/cmake/cmake', branch: 'v3.21.0-rc2')
cheribuildProject(target: 'cmake',
                  targetArchitectures: ["amd64", "riscv64-purecap", "riscv64", "native"], // TODO: morello
                  scmOverride: cmakeRepo,
                  skipArchiving: false,
                  runTests: false,
                  setGitHubStatus: false, // external repo
)
