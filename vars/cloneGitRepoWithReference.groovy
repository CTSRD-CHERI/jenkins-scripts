def call(Map args) {
    def scm = gitRepoWithLocalReference(args)
    return checkout(scm: scm, poll: args.getOrDefault("poll", true), changelog: args.getOrDefault("changelog", true))
}
