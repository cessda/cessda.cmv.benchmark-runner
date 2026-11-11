## Fetch OAI-PMH Identifiers

This page is the first stage of the benchmark pipeline: it queries an
OAI-PMH endpoint for the record identifiers that **Run assessment**
will later submit to the FAIR Champion benchmark API.

### Set discovery is always live

The set list shown below the endpoint field is never a saved or
hand-maintained list — it is fetched fresh from the endpoint's own
`ListSets` response every time this page loads or you change the
URL. This means it can never drift out of sync with what the
endpoint actually offers, even if the endpoint adds, renames, or
removes sets.

Each set is shown with its own human-readable name, exactly as the
endpoint itself reports it.

### Changing the endpoint

The URL field is pre-filled with your organisation's configured
OAI-PMH endpoint. You can edit it before loading sets or fetching
identifiers, but any change here applies to this one run only — it
does not update your organisation's saved default, so the field will
reset to your organisation's own endpoint the next time you open this
page.

Click **↻ Load sets** after changing the URL to see that endpoint's
own set list.

### Selecting sets

Use the checkboxes to choose which sets to fetch identifiers for, or
the **All** / **None** buttons above the list to select or clear
every set at once. At least one set must be selected before **Fetch
identifiers** becomes available.

> Tip: If you only need to refresh one or two sets rather than an
> organisation's entire catalogue, select just those sets — an
> unselected set's existing identifier file is left untouched.

### What happens when you fetch

Clicking **Fetch identifiers** writes one file per selected set,
ready for the next pipeline stage. If several sets are selected and
one of them fails — for example, because it briefly becomes
unavailable — the others are not affected: the result message reports
how many sets succeeded and lists which, if any, failed and why.

### Next step

Once identifiers have been fetched, go to the dashboard and click
**Run assessment** to submit them to the FAIR Champion benchmark API.
