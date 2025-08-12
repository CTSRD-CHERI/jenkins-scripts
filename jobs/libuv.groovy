@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'hour', userBoost: true]),
        copyArtifactPermission('*'),
])

// NB: Currently overrides https://github.com/libuv/libuv.git in cheribuild
def libuvRepo = gitRepoWithLocalReference(url: 'https://github.com/arichardson/libuv.git', branch: 'v1.x')
cheribuildProject(target: 'libuv',
                  scmOverride: libuvRepo,
                  targetArchitectures: ["aarch64", "amd64", "morello-purecap", "riscv64", "riscv64-purecap"],
                  setGitHubStatus: false, // external repo
)
