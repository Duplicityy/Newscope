#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@protocol NSCGameDelegate <NSObject>
- (void)engineStatus:(NSString *)status;
- (void)engineFrame:(CGImageRef)frame width:(NSInteger)width height:(NSInteger)height;
- (void)engineEditor:(NSString *)editor regions:(NSArray<NSNumber *> *)regions layoutSelection:(NSInteger)selection;
@end

@interface NSCGameRuntime : NSObject
@property (nonatomic, weak) id<NSCGameDelegate> delegate;
@property (nonatomic, readonly, getter=isRunning) BOOL running;
- (void)start;
- (void)setVisible:(BOOL)visible;
- (void)touch:(NSInteger)phase finger:(NSInteger)finger point:(CGPoint)point;
- (void)moveFingers:(NSDictionary<NSNumber *, NSValue *> *)points;
- (void)setCanvas:(CGSize)canvas gestureThreshold:(CGFloat)threshold;
- (void)setViewport:(CGSize)viewport scale:(CGFloat)scale;
- (void)setArmMillis:(NSInteger)arm menuMillis:(NSInteger)menu sensitivity:(CGFloat)sensitivity observer:(BOOL)observer;
- (void)setLayoutEditing:(BOOL)editing save:(BOOL)save;
- (void)placeControl:(NSInteger)control atPoint:(CGPoint)point;
- (void)setMobileHd:(BOOL)enabled ultraMask:(NSInteger)ultra shadows:(BOOL)shadows;
- (void)event:(NSInteger)kind x:(NSInteger)x y:(NSInteger)y value:(NSInteger)value text:(nullable NSString *)text;
- (void)plugin:(NSDictionary<NSString *,id> *)request completion:(void (^)(NSDictionary<NSString *,id> *reply))completion;
@end

NS_ASSUME_NONNULL_END
