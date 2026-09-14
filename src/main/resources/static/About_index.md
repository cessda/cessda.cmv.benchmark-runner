## FAIR Benchmark Dashboard

This is the overview page for your organisation's FAIR assessment
results. It summarises every set that has been through the pipeline,
lets you drill into any one of them, and is where you trigger the
three pipeline stages when you need fresh results.

### Overall Summary

The row of tiles at the top of the page covers the whole
organisation, across every set:

- **Total Records** — how many records have been assessed in total.
- **Pass Rate** / **Fail Rate** / **Indeterminate Rate** — the share
  of individual FAIR tests, across all records, that passed, failed,
  or could not be determined.
- **Sets** — how many sets have results.
- **Maturity Levels** — how many records fall into each maturity
  level, based on which tests they pass.

### FAIR Principle Scores

Below the summary, four bars show the pass rate for each FAIR
principle — Findable, Accessible, Interoperable, Reusable — averaged
across every record and every set.

### The results table

Each row is one set (for example, one language or one collection,
depending on how your organisation's OAI-PMH catalogue is
organised). It shows the record count, a pass/fail/indeterminate
result distribution bar, per-FAIR-principle dots, and the set's
overall maturity level.

Click anywhere on a row, or press Enter or Space while it has focus,
to open that set's own detail view.

> Tip: Click a column heading — Pass, Fail, Indet., or Maturity — to
> sort the table by that column. Click again to reverse the order.

### Running the pipeline

Producing these results is a three-stage pipeline, each step
triggered from a button in the header:

1. **Fetch identifiers** — opens a separate page for discovering and
   selecting which sets to pull record identifiers for from an
   OAI-PMH endpoint. See that page's own guide for details.
2. **Run assessment** — submits the fetched identifiers to the FAIR
   Champion benchmark assessment API. This can take a while for large
   sets; you can leave the tab open and check back later.
3. **Generate manifest** — turns the raw assessment results into the
   summary and per-page data this dashboard reads. Run this last,
   after an assessment run finishes, to bring the dashboard's numbers
   up to date.

Each stage asks you to confirm the settings it will use — the
algorithm and runner URIs, or which sets to process — before it
starts, and changes made in that confirmation only apply to that one
run.

### Your API key

Your organisation's API key identifies which results this dashboard
shows you. It is entered once and stored only for the current
browser session (it is cleared when you close the tab), so it never
needs to be typed into a URL or saved to disk. Use **Change key** in
the header if you need to switch to a different key, for example to
check another organisation's results if you hold more than one key.

> Tip: Each API key belongs to exactly one organisation. There is no
> way to see another organisation's data with your own key — the key
> itself is what keeps every organisation's results separate.
