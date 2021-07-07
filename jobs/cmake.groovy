@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'hour', userBoost: true]),
        copyArtifactPermission('*'),
])

def cmakeRepo = gitRepoWithLocalReference(url: 'https://github.com/CTSRD-CHERI/riscv-pk.git', branches: ["*/release"])
cheribuildProject(target: 'cmake',
                  targetArchitectures: ["amd64", "riscv64-purecap", "riscv64", "native"], // TODO: morello
                  scmOverride: cmakeRepo,
                  skipArchiving: false,
                  runTests: false,
                  setGitHubStatus: false, // external repo
)
