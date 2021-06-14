@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([
        rateLimitBuilds([count: 2, durationName: 'hour', userBoost: true]),
        [$class: 'GithubProjectProperty', projectUrlStr: 'https://github.com/CTSRD-CHERI/qtbase'],
        copyArtifactPermission('*'),
])

cheribuildProject(target: 'qtbase',
                  // targetArchitectures: ["amd64", "riscv64-purecap"],
                  targetArchitectures: ["amd64"],
                  customGitCheckoutDir: "qt5/qtbase", // cheribuild expects a different directory
                  extraArgs: '--qtbase/build-tests --qtbase/minimal --qtbase/build-type=Release', // TODO: also build the GUI bits
                  skipArchiving: true,
                  runTests: true,
                  cheribsdBranch: 'dev', // just for testing
                  testTimeout: 4 * 60 * 60, // increase the test timeout to 4 hours (CHERI can take a loooong time)
                  beforeTests: "rm -fv test-results.xml",
                  testExtraArgs: "--junit-xml=test-results.xml",
                  junitXmlFiles: "test-results.xml"
                  )
