import 'package:flutter/services.dart';

class CameraException extends PlatformException {
  CameraException(
      { String code,
       String message,
      String stacktrace,
      dynamic details})
      : super(
          code: code,
          message: message,
          stacktrace: stacktrace,
          details: details,
        );
}
