import 'package:flutter/material.dart';

import '../camera_controller.dart';
import '../params/camera_args.dart';

/// A widget showing a live camera preview.
class CameraView extends StatelessWidget {
  /// The controller of the camera.
  final CameraController controller;

  double zoom = 1.0;
  double _scaleFactor = 1.0;


  /// Create a [CameraView] with a [controller], the [controller] must has been initialized.
  CameraView(this.controller);

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: controller.args,
      builder: (context, value, child) => _build(context, value as CameraArgs),
    );
  }

  Widget _build(BuildContext context, CameraArgs value) {
    if (value == null) {
      return Container(color: Colors.black);
    } else {
      return
        ClipRect(
        child: Transform.scale(
          scale: value.size.fill(MediaQuery.of(context).size),
          child: Center(
            child: AspectRatio(
              aspectRatio: value.size.aspectRatio,
              child: Texture(textureId: value.textureId),
            ),
          ),
        ),
      );
    }
  }
}

extension on Size {
  double fill(Size targetSize) {
    if (targetSize.aspectRatio < aspectRatio) {
      return targetSize.height * aspectRatio / targetSize.width;
    } else {
      return targetSize.width / aspectRatio / targetSize.height;
    }
  }
}
