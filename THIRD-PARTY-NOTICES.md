# Third-Party Notices

## OSRS TCG image cache compatibility

`SharedNpcImageCache` includes cache-key and disk-layout behavior derived from the OSRS TCG `WikiImageCacheService` by Azderi, copyright 2026, used under the BSD 2-Clause License.

It also preserves compatibility with the TCG Locked shared NPC image cache by s59, copyright 2026. The implementation is included locally because RuneLite Plugin Hub plugins use isolated classloaders and cannot share implementation classes at runtime.

The full license for this project is available in [LICENSE](LICENSE).
