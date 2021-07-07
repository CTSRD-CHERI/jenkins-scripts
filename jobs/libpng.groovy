@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'hour', userBoost: true]),
        [$class: 'GithubProjectProperty', projectUrlStr: 'https://github.com/CTSRD-CHERI/libpng'],
        copyArtifactPermission('*'),
])

cheribuildProject(target: 'libpng',
                  targetArchitectures: ["amd64", "riscv64-purecap", "riscv64", "native"], // TODO: morello
                  skipArchiving: true,
                  runTests: true,
                  testTimeout: 4 * 60 * 60,
                  // TODO: beforeTests: "rm -fv test-results.xml", testExtraArgs: "--junit-xml=test-results.xml", junitXmlFiles: "test-results.xml"
)
