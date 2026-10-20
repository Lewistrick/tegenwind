# Notes for agents working on Tegenwind

## Style

Before any change that affects how the app looks or reads (screens, cards, colours, charts, buttons,
wording on screen), read `docs/STYLE.md` and follow it:
- Reuse the shared components in `app/src/main/java/com/tegenwind/app/ui/Components.kt` and
  `ui/TimeSeriesChart.kt` instead of writing local copies.
- Use colours only through `MaterialTheme.colorScheme` or the names in `ui/theme/Color.kt`, with
  the meanings the guide gives them.
- If the change needs a rule the guide doesn't have (a new colour, component, chart element or
  text pattern), add the rule to `docs/STYLE.md` in the same change.
- Check the result on the phone as described in the guide's last section.

## Comparisons

Compare the rider only with their own real (not simulated) rides: their usual, their median, best
or last ride, an earlier ride. What the arrival time is based on is allowed because it is learned from those
rides and corrected for the weather. Never show a comparison with a model rider, a reference value
or bare physics (no form percentage, no "vs physics"). The full rule is "Compare only with your own
rides" in `docs/STYLE.md`.
