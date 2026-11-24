# Yellow Click Fix
A RuneLite plugin to aid in the Jagex's "yellow click" issue which is inherent
to their client. This issue often happens when one is moving their cursor
quickly between two locations which have different menu options. For example,
from the ground to an NPC they want to attack. If the click arrives less than
one client cycle after the cursor reached the NPC, the menu entries are not
rebuilt and are stale. This generally results in a yellow click "Walk here"
through an NPC instead of the intended "Attack".

![Yellow clicking a blob in the inferno](img/blob.png)

---

![Plugin options](img/plugin-panel.png)

The plugin offers two togglable settings:
* Rescue stale clicks
  * Attempt to wait a client cycle for NPC menu options to populate before sending the click
* Chat debug feedback
  * Print out a debug message each time the plugin activates
