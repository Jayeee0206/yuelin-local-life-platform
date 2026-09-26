# Third-party notices

YueLin Local Life project code is licensed under the repository [MIT License](LICENSE). Third-party software, fonts, container images and media remain subject to their own licenses and terms; the project license does not replace them.

## Browser assets committed to this repository

The following prebuilt assets are vendored under `frontend/` and are not installed through `package-lock.json`:

| Component | Identified version | Repository paths | License |
|---|---|---|---|
| Vue.js | 2.5.16 | `frontend/js/vue.js` | MIT |
| Axios | 0.18.0 | `frontend/js/axios.min.js` | MIT |
| Element UI | JavaScript banner identifies 2.15.3; some CSS/font metadata identifies 2.6.2 | `frontend/js/element.js`, `frontend/css/element.css`, `frontend/css/fonts/` | MIT |

Upstream projects:

- Vue.js: <https://github.com/vuejs/vue>
- Axios: <https://github.com/axios/axios>
- Element UI: <https://github.com/ElemeFE/element>

Preserve upstream copyright and license banners when redistributing or replacing these files. Because these are manually committed builds, normal npm lockfile auditing does not inventory them. Their versions are old and should be reviewed, upgraded or replaced before an Internet-facing production deployment.

## Package-managed dependencies

Java dependencies are declared in `pom.xml`. Node-based quality tooling is declared in `package.json` and locked in `package-lock.json`. Those dependency trees include additional transitive components under their respective licenses. Generate a software bill of materials or license report from the exact release commit when distribution policy requires a complete inventory.

## Container images

`compose.yaml` references upstream images for Nginx, MySQL, Redis and RabbitMQ and builds the application image from an upstream Java base image. The image binaries are not copied into this Git repository. Anyone pulling or redistributing images must review the license notices and distribution terms in the exact pinned tag or digest.

The repository does **not** vendor a standalone Nginx operating-system distribution. The project-specific proxy configuration is `deploy/nginx/default.conf`.

## Demonstration media

The repository's demonstration images are simple SVG placeholders drawn for this project. Seed data in `src/main/resources/db/yuelin_local_life.sql` uses those local placeholders and does not request third-party photo hosts. User-uploaded media is stored outside Git and remains the uploader's responsibility to review before publication.

## No endorsement

Third-party names and trademarks identify compatibility only and do not imply endorsement by their owners.
