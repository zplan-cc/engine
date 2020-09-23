// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import "GoldenImage.h"

#import <XCTest/XCTest.h>
#include <sys/sysctl.h>

static const double kRmseThreshold = 0.5;

@interface GoldenImage ()

@end

@implementation GoldenImage

- (instancetype)initWithGoldenNamePrefix:(NSString*)prefix {
  self = [super init];
  if (self) {
    _goldenName = [prefix stringByAppendingString:_platformName()];
    NSBundle* bundle = [NSBundle bundleForClass:[self class]];
    NSURL* goldenURL = [bundle URLForResource:_goldenName withExtension:@"png"];
    NSData* data = [NSData dataWithContentsOfURL:goldenURL];
    _image = [[UIImage alloc] initWithData:data];
  }
  return self;
}

- (BOOL)compareGoldenToImage:(UIImage*)image {
  if (!self.image || !image) {
    return NO;
  }
  CGImageRef imageRefA = [self.image CGImage];
  CGImageRef imageRefB = [image CGImage];

  NSUInteger widthA = CGImageGetWidth(imageRefA);
  NSUInteger heightA = CGImageGetHeight(imageRefA);
  NSUInteger widthB = CGImageGetWidth(imageRefB);
  NSUInteger heightB = CGImageGetHeight(imageRefB);

  if (widthA != widthB || heightA != heightB) {
    return NO;
  }
  NSUInteger bytesPerPixel = 4;
  NSUInteger size = widthA * heightA * bytesPerPixel;
  NSMutableData* rawA = [NSMutableData dataWithLength:size];
  NSMutableData* rawB = [NSMutableData dataWithLength:size];

  if (!rawA || !rawB) {
    return NO;
  }

  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();

  NSUInteger bytesPerRow = bytesPerPixel * widthA;
  NSUInteger bitsPerComponent = 8;
  CGContextRef contextA =
      CGBitmapContextCreate(rawA.mutableBytes, widthA, heightA, bitsPerComponent, bytesPerRow,
                            colorSpace, kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);

  CGContextDrawImage(contextA, CGRectMake(0, 0, widthA, heightA), imageRefA);
  CGContextRelease(contextA);

  CGContextRef contextB =
      CGBitmapContextCreate(rawB.mutableBytes, widthA, heightA, bitsPerComponent, bytesPerRow,
                            colorSpace, kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
  CGColorSpaceRelease(colorSpace);

  CGContextDrawImage(contextB, CGRectMake(0, 0, widthA, heightA), imageRefB);
  CGContextRelease(contextB);

  const char* apos = rawA.mutableBytes;
  const char* bpos = rawB.mutableBytes;
  double sum = 0.0;
  for (size_t i = 0; i < size; ++i, ++apos, ++bpos) {
    // Skip transparent pixels.
    if (*apos == 0 && *bpos == 0 && i % 4 == 0) {
      i += 3;
      apos += 3;
      bpos += 3;
    } else {
      double aval = *apos;
      double bval = *bpos;
      double diff = aval - bval;
      sum += diff * diff;
    }
  }
  double rmse = sqrt(sum / size);
  return rmse <= kRmseThreshold;
}

NS_INLINE NSString* _platformName() {
  NSString* simulatorName =
      [[NSProcessInfo processInfo].environment objectForKey:@"SIMULATOR_DEVICE_NAME"];
  if (simulatorName) {
    return [NSString stringWithFormat:@"%@_simulator", simulatorName];
  }

  size_t size;
  sysctlbyname("hw.model", NULL, &size, NULL, 0);
  char* answer = malloc(size);
  sysctlbyname("hw.model", answer, &size, NULL, 0);

  NSString* results = [NSString stringWithCString:answer encoding:NSUTF8StringEncoding];
  free(answer);
  return results;
}

@end
