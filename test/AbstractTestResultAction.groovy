package hudson.tasks.test

// HACK to make the getTestStatus work (since I can't just add a maven dependency because jenkins feels like
// packaging .jar files is too hard, let's invent our own .hpi format instead)
class AbstractTestResultAction {}
