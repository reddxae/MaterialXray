# Performance

Use Jetpack Compose best practices when working on UI:
- Use `remember` to minimize expensive calculations
- Use lazy layout keys
- Use `derivedStateOf` to limit recompositions
- Defer reads as long as possible
- Avoid backwards writes
- Be vary of LazyColumns and their caveats, use recycleviews if necessary

# Commits

Use conventional commits.
Make your commits atomic - one commit is one 'thing' done.

# Workflow

After you're done working on something, see if there's a device connected over ADB. If there is - build a *release* version of the app, install it and launch it.
