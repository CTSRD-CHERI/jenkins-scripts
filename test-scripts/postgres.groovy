@Library('ctsrd-jenkins-scripts') _

// Set the default job properties (work around properties() not being additive but replacing)
setDefaultJobProperties([rateLimitBuilds([count: 2, durationName: 'hour', userBoost: true]),
                         [$class: 'GithubProjectProperty', projectUrlStr: 'https://github.com/CTSRD-CHERI/postgres'],
                         // copyArtifactPermission('*'),
])

def cleanupScript = '''
# remove the 600+ useless header files
rm -rf tarball/opt/*/include
'''


cheribuildProject(target: 'postgres', targetArchitectures: ["mips-nocheri", "mips-purecap"],
        // extraArgs: '--with-libstatcounters --postgres/no-debug-info --postgres/no-assertions',
        extraArgs: '--no-with-libstatcounters --postgres/assertions --postgres/linkage=dynamic',
        beforeTarball: cleanupScript,
        skipArchiving: true,
        runTests: true,
        useNewLLVMJobs: true,
        beforeBuild: 'ls -la $WORKSPACE',
        testTimeout: 4 * 60 * 60, // increase the test timeout to 4 hours (CHERI can take a loooong time)
        /* sequential: true, // for now run all in order until we have it stable */)

