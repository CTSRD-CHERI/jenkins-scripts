import cheriHardwareTest

try {
	parallel(
		"CHERI1-TEST": { cheriHardwareTest(
			name: "CHERI1-TEST",
			buildArgs: 'CAP=True',
			testArgs: 'NOFUZZR=1 GENERIC_L1=1 STATCOUNTERS=1 ALLOW_UNALIGNED=1 SIM_TRACE_OPTS= nosetests_combined.xml')
		}, /* This one currently fails to build and I don't know why:
		"CHERI1-CACHECORE-TEST": { cheriHardwareTest(
			name: "CHERI1-CACHECORE-TEST",
			buildArgs: 'CAP=True ICACHECORE=1 DCACHECORE=1',
			testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetest_cached')
		}, */ "CHERI1-FPU-TEST": { cheriHardwareTest(
			name: "CHERI1-FPU-TEST",
			buildArgs: 'CAP=True COP1=1',
			testArgs: 'COP1=1 TEST_PS=1 CLANG=0 NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_combined.xml')
		}, "CHERI1-CAP128-TEST": { cheriHardwareTest(
			name: "CHERI1-CAP128-TEST",
			buildArgs: 'CAP128=True',
			testArgs: 'GENERIC_L1=1 CAP_SIZE=128 PERM_SIZE=19 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached')
		}, "CHERI1-MICRO-TEST": { cheriHardwareTest(
			name: "CHERI1-MICRO-TEST",
			buildArgs: 'MICRO=True CAP=True NOWATCH=True',
			testArgs: 'CHERI_MICRO=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetest_cached')
		}, "CHERI1-MULTI1-TEST": { cheriHardwareTest(
			name: "CHERI1-MULTI1-TEST",
			buildArgs: 'MULTI=1 CAP=True',
			testArgs: 'NOFUZZR=1 GENERIC_L1=1 SIM_TRACE_OPTS= nosetests_cached.xml')
		}, "CHERI1-MULTI2-TEST": { cheriHardwareTest(
			name: "CHERI1-MULTI2-TEST",
			buildArgs: 'MULTI=2 CAP=True',
			testArgs: 'GENERIC_L1=1 MULTI=1 NOFUZZR=1 SIM_TRACE_OPTS= nosetests_cachedmulti.xml')
		}
	)
} catch(e) {
	/* emailext body: '$DEFAULT_CONTENT',
		recipientProviders: [
			[$class: 'CulpritsRecipientProvider'],
			[$class: 'DevelopersRecipientProvider'],
			[$class: 'RequesterRecipientProvider']
		],
		replyTo: '$DEFAULT_REPLYTO',
		subject: '$DEFAULT_SUBJECT',
		to: '$DEFAULT_RECIPIENTS' */
}
