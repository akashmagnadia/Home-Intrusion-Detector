# Home-Intrusion-Detector

There are many home security solutions out there that can often be expensive and not remote enough to be placed in any location that you want.
Maybe you just want something cheap and simple and if that's the case then this the solution you are looking for.
All you need is a spare phone that runs on Android 4.4 or above and repurpose it for a security device.

Once the monitoring system is activated you will get an alert if the phone sees a person, hears a glass breaking, hears a doorbell, hears a knock such as a door knock.
No more downloading motion detector app that gives false positives such as when a curtain moves. When a person is detected you get sent an email with a picture of what the phone saw.

The algorithms in this application are trained on Tensorflow using machine learning, which is one of the best machine learning tools.
The TensorFlow file is converted to lite version so that it can be used in mobile applications with low latency detection.
All the detection for this application occurs offline.

Sources used to create this project:
<br>
https://github.com/tensorflow/tensorflow/tree/master/tensorflow/examples
<br>
https://tensorflow-object-detection-api-tutorial.readthedocs.io/en/latest/training.html#preparing-workspace
<br>
https://github.com/tensorflow/models/tree/master/research/object_detection
<br>
https://github.com/googlearchive/android-Camera2Basic?utm_campaign=adp_series_how_to_camera2_031016&utm_source=medium&utm_medium=blog
<br>
https://github.com/JakeWharton/ProcessPhoenix
<br>