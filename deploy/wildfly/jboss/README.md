# /jboss/

## Sinn und Zweck

Dieses Verzeichnis ist ausschließlich für Dateien zur Initialisierung des WildFly's.
Dateien mit der Endung `.cli` werden bei jeden Start des WildFly's ausgeführt, wobei jede Datei nur 1x ausgeführt wird.
Auf diese Weise lassen sich zum Beispiel `datasources`, `logger` oder auch `deployment-overlays` anlegen.
Weitere Details siehe hier: <https://access.redhat.com/documentation/en-us/red_hat_jboss_enterprise_application_platform/7.2/html/management_cli_guide/how_to_cli>

## Zusatz-Wissen

Über einen Schalter in Form der ENV-Variable `CLI_FILTER` können weitere Datei-Endungen zur Ausführung hinzugefügt werden.
Dateien mit der Endung `.cli` werden auch mit setzen dieses Filters weiter berücksichtigt.

## License

**License:** AGPLv3, <https://www.gnu.org/licenses/agpl-3.0.en.html>
**Copyright:** 2014 - 2021 University Medicine Greifswald
**Contact:** <https://www.ths-greifswald.de/kontakt/>
