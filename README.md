# Animan

Animan est une application web Spring Boot pour rechercher des animes, consulter leurs episodes, gerer une bibliotheque personnelle et lancer des telechargements depuis plusieurs sources.

Le projet combine une interface Thymeleaf, un gestionnaire de telechargements avec progression en temps reel, une base H2 locale persistante et des services de scraping pour `french-manga.net` et `voir-anime.to`.

## Fonctionnalites

- Recherche d'animes depuis `french-manga.net` et `voir-anime.to`
- Affichage des dernieres sorties par source
- Page detail avec episodes, versions, progression et favoris
- Telechargement d'un episode ou d'une selection d'episodes
- File de telechargement avec pause, reprise, annulation, nettoyage et priorisation
- Suivi de progression en temps reel via SSE
- Limite de vitesse globale ou par tache
- Planification de telechargements
- Detection de doublons locaux
- Telechargement optionnel des sous-titres
- Lecture locale des videos telechargees avec support HTTP Range
- Bibliotheque avec favoris, historique et episodes vus/telecharges
- Watchdog pour telecharger automatiquement les nouveaux episodes des favoris suivis
- Organisation optionnelle compatible Plex/Jellyfin avec fichiers `.nfo`

## Stack technique

- Java 17
- Spring Boot 3.4.5
- Spring Web
- Thymeleaf
- Spring Data JPA
- H2 Database
- Jsoup
- Playwright
- Lombok
- Maven Wrapper

## Prerequis

- Java 17 ou plus recent
- Connexion internet pour acceder aux sources anime
- Maven n'est pas obligatoire si vous utilisez le wrapper `mvnw.cmd`
- FFmpeg optionnel, utilise si disponible pour muxer les sous-titres dans un fichier `.mkv`

Sur Windows, verifiez Java avec :

```powershell
java -version
```

## Installation

Clonez le projet puis placez-vous dans le dossier :

```powershell
cd D:\Projets\Animan
```

Installez les dependances et compilez :

```powershell
.\mvnw.cmd clean package
```

## Lancement

En mode developpement :

```powershell
.\mvnw.cmd spring-boot:run
```

Ou apres compilation :

```powershell
java -jar target\animan-2.0.0.jar
```

L'application est ensuite disponible sur :

```text
http://localhost:8080
```

## Pages principales

- `/` : accueil, recherche, favoris rapides et dernieres sorties
- `/search?q=naruto&source=french-manga` : recherche par source
- `/anime?url=...&source=french-manga` : detail d'un anime
- `/downloads` : gestionnaire de telechargements
- `/library` : favoris et bibliotheque
- `/history` : historique
- `/h2-console` : console H2 locale

## API principales

### Anime

- `GET /api/search?q=...&source=french-manga`
- `GET /api/voiranime/search?q=...`
- `GET /api/voiranime/anime?url=...`
- `GET /api/voiranime/download-info?episodeUrl=...`
- `GET /api/voiranime/qualities?episodeUrl=...`

### Telechargements

- `POST /api/download`
- `POST /api/download/batch`
- `GET /api/download/status`
- `GET /api/download/stream`
- `POST /api/download/{id}/pause`
- `POST /api/download/{id}/resume`
- `DELETE /api/download/{id}`
- `DELETE /api/download/{id}/remove`
- `POST /api/download/pause-all`
- `POST /api/download/resume-all`
- `POST /api/download/clean`
- `POST /api/download/{id}/move-to-top`
- `POST /api/download/move-series-to-top`
- `GET /api/download/settings`
- `POST /api/download/settings`
- `GET /api/episode/size?url=...`

### Bibliotheque

- `GET /api/favorites`
- `POST /api/favorites`
- `DELETE /api/favorites`
- `GET /api/favorites/check?url=...`
- `PATCH /api/favorites/auto-download`
- `POST /api/progress/watched`
- `GET /api/progress?animeUrl=...`
- `GET /api/progress/downloaded?animeUrl=...`
- `GET /api/history`

### Media local

- `GET /api/media/stream/{id}`
- `GET /api/media/subtitles/{id}`
- `GET /api/media/subtitle/{id}`
- `GET /api/media/subtitle/{id}/{index}`

## Configuration

La configuration se trouve dans :

```text
src/main/resources/application.properties
```

Parametres importants :

```properties
server.port=8080
animan.base-url=https://w16.french-manga.net
animan.voir-anime-url=https://voir-anime.to
animan.download-dir=${user.home}/Downloads/Animan
animan.max-concurrent-downloads=3
animan.connection-timeout=15000
animan.read-timeout=30000
animan.watchdog-interval-ms=3600000
```

La base H2 est stockee par defaut ici :

```text
${user.home}/.animan/db/animan
```

Les telechargements sont stockes par defaut ici :

```text
${user.home}/Downloads/Animan
```

## Base de donnees H2

La console H2 est active sur :

```text
http://localhost:8080/h2-console
```

Parametres par defaut :

```text
JDBC URL : jdbc:h2:file:${user.home}/.animan/db/animan;AUTO_SERVER=TRUE
User     : sa
Password : vide
```

## Developpement

Lancer les tests :

```powershell
.\mvnw.cmd test
```

Compiler sans lancer les tests :

```powershell
.\mvnw.cmd package -DskipTests
```

Structure principale :

```text
src/main/java/com/pauldev/animan
  config/       Configuration Spring
  controller/   Controleurs MVC et API REST
  model/        Modeles metier et entites
  repository/   Repositories Spring Data JPA
  service/      Scraping, bibliotheque, watchdog et telechargements

src/main/resources
  templates/    Vues Thymeleaf
  static/       CSS et JavaScript servis publiquement
```

## Notes

Animan depend de sites externes. Leur structure HTML, leurs protections anti-bot ou leurs URLs peuvent changer et casser temporairement certaines fonctionnalites de recherche, detail ou telechargement.

Utilisez ce projet dans le respect des conditions d'utilisation des sites consultes et des droits applicables aux contenus telecharges.

## Licence

Aucune licence n'est definie pour le moment.
