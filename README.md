# Quizzunio

Quizzunio es una aplicación Android en desarrollo que ayuda a estudiar a partir de documentos. La idea es convertir apuntes y material de clases en cuestionarios que se puedan responder de inmediato, para repasar los contenidos de una forma más activa.

## ¿Cómo funciona?

1. Selecciona uno o más archivos de estudio desde el dispositivo. El proyecto contempla archivos **PDF, TXT, DOCX y PPTX** con texto legible.
2. Elige entre 1 y 15 preguntas y genera el cuestionario.
3. Responde las preguntas de alternativas y revisa tus resultados.
4. Vuelve a los cuestionarios guardados en el dispositivo para seguir practicando.

La aplicación está en desarrollo: las pantallas y funcionalidades pueden cambiar mientras se completa y prueba el proyecto.

## Versión gratuita: generación local

La versión en desarrollo procesa el material en el dispositivo y genera preguntas mediante reglas, sin utilizar inteligencia artificial ni servicios de generación remotos. El generador extrae frases del material, oculta conceptos y propone alternativas para completarlas.

La calidad de las preguntas depende del contenido y de cómo esté escrito. Conviene revisar las preguntas y sus respuestas antes de usarlas para estudiar o evaluar. Los PDF escaneados necesitan que se reconozca su texto con OCR antes de importarlos; Quizzunio no realiza ese reconocimiento actualmente. Los archivos protegidos o sin texto legible tampoco se pueden procesar.

## Versión premium

Las cuentas Premium pueden elegir generación local o generación con IA en QuizzunioServer. Android extrae el texto en el dispositivo y consulta su límite de bytes UTF-8 antes de enviarlo. Las cuentas Free autenticadas pueden guardar en la nube los cuestionarios generados localmente.

Los cuestionarios conservan acentos, escrituras no latinas, símbolos y emojis. El [contrato OpenAPI](specs/openapi.json) especifica las unidades y los límites de cada campo.

## Tecnologías

- **Plataforma:** Android, desde Android 9 (API 28).
- **Lenguaje:** Kotlin.
- **Interfaz:** Jetpack Compose y Material 3.
- **Navegación y estado:** Navigation Compose y ViewModel.
- **Almacenamiento:** datos de los cuestionarios guardados localmente en el dispositivo.
- **Compilación:** Gradle con Kotlin DSL.

## Abrir el proyecto

1. Clona el repositorio y abre su carpeta en Android Studio.
2. Instala el SDK de Android 37 y permite que Android Studio sincronice las dependencias de Gradle. El proyecto configura Java 25 para el proceso de Gradle.
3. Selecciona un dispositivo o emulador con Android 9 o superior y ejecuta el módulo `app`.

## Estructura principal

- `app/src/main/java/cl/quizzunio/study/`: lectura de documentos, generación local y gestión de cuestionarios.
- `app/src/main/java/cl/quizzunio/ui/`: pantallas, navegación y tema visual.
- `app/src/main/java/cl/quizzunio/settings/`: preferencias de la aplicación.
- `docs/maquetas/`: diseños de las pantallas en sus versiones clara y oscura.

