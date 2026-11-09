# Iris Cam UI Test

Isolated test-app copy of Iris Cam build #457, based on source commit [`433f1fb`](https://github.com/mrjdm2007/Iris-Cam/commit/433f1fb).

- Test application ID: `com.iris.cam.uitest`
- Production repository is not modified by test-app changes.
- Portrait Photo Mode uses automatic scene-aware blur: one face 80%, face in a wide scene 40%, groups 35%, and no detected face 0%. The slider remains available; 40% selects Auto and another value applies a manual override.
