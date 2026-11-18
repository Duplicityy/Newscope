#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import "GameRuntime.h"
#import "PluginControls.h"
#import <math.h>

@interface NSCGameCanvas : UIView
@property (nonatomic, weak) NSCGameRuntime *runtime;
@property (nonatomic) CGSize frameSize;
@property (nonatomic) BOOL editing;
@property (nonatomic) NSInteger placing;
@property (nonatomic) NSInteger selection;
@property (nonatomic, copy) NSArray<NSNumber *> *regions;
@property (nonatomic, readonly) CAShapeLayer *editOutline;
- (void)cancelTouches;
@end

@implementation NSCGameCanvas {
    NSMutableDictionary<NSValue *, NSNumber *> *_fingers;
    CAShapeLayer *_editOutline;
    CAShapeLayer *_selectedOutline;
    NSInteger _nextFinger;
}

- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) {
        _fingers = [NSMutableDictionary dictionary];
        self.backgroundColor = UIColor.blackColor;
        self.multipleTouchEnabled = YES;
        self.layer.contentsGravity = kCAGravityResizeAspect;
        self.layer.magnificationFilter = kCAFilterLinear;
        _placing = -1;
        _selection = -1;
        _editOutline = [CAShapeLayer layer];
        _editOutline.strokeColor = UIColor.systemTealColor.CGColor;
        _editOutline.fillColor = UIColor.clearColor.CGColor;
        _editOutline.lineWidth = 1;
        _selectedOutline = [CAShapeLayer layer];
        _selectedOutline.strokeColor = UIColor.systemYellowColor.CGColor;
        _selectedOutline.fillColor = UIColor.clearColor.CGColor;
        _selectedOutline.lineWidth = 2;
        [self.layer addSublayer:_editOutline];
        [self.layer addSublayer:_selectedOutline];
    }
    return self;
}

- (BOOL)map:(UITouch *)touch allowOutside:(BOOL)allowOutside point:(CGPoint *)point {
    if (_frameSize.width <= 0 || _frameSize.height <= 0) return NO;
    CGRect image = AVMakeRectWithAspectRatioInsideRect(_frameSize, self.bounds);
    CGPoint local = [touch locationInView:self];
    if (CGRectIsEmpty(image) || (!allowOutside && !CGRectContainsPoint(image, local))) return NO;
    point->x = MAX(0, MIN(_frameSize.width - 1, (local.x - CGRectGetMinX(image)) * _frameSize.width / CGRectGetWidth(image)));
    point->y = MAX(0, MIN(_frameSize.height - 1, (local.y - CGRectGetMinY(image)) * _frameSize.height / CGRectGetHeight(image)));
    return YES;
}

- (void)setFrameSize:(CGSize)size {
    if (!CGSizeEqualToSize(_frameSize, size)) [self cancelTouches];
    _frameSize = size;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    [self redrawOutlines];
}

- (void)setEditing:(BOOL)value {
    _editing = value;
    if (!value) _placing = _selection = -1;
    [self redrawOutlines];
}

- (void)setRegions:(NSArray<NSNumber *> *)regions {
    _regions = [regions copy];
    if (_editing) [self redrawOutlines];
}

- (void)setSelection:(NSInteger)selection {
    if (_selection != selection) {
        _selection = selection;
        if (_editing) [self redrawOutlines];
    }
}

- (void)redrawOutlines {
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    _editOutline.frame = _selectedOutline.frame = self.bounds;
    UIBezierPath *all = [UIBezierPath bezierPath], *selected = [UIBezierPath bezierPath];
    if (_editing && _frameSize.width > 0 && _frameSize.height > 0) {
        CGRect image = AVMakeRectWithAspectRatioInsideRect(_frameSize, self.bounds);
        CGFloat xScale = CGRectGetWidth(image) / _frameSize.width;
        CGFloat yScale = CGRectGetHeight(image) / _frameSize.height;
        for (NSUInteger i = 8; i + 4 < _regions.count; i += 5) {
            NSInteger control = _regions[i + 4].integerValue - 100;
            if (control < 0 || control >= 100) continue;
            CGRect frame = CGRectMake(CGRectGetMinX(image) + _regions[i].doubleValue * xScale,
                CGRectGetMinY(image) + _regions[i+1].doubleValue * yScale,
                _regions[i+2].doubleValue * xScale, _regions[i+3].doubleValue * yScale);
            if (!CGRectIsEmpty(frame)) [(control == _selection ? selected : all)
                appendPath:[UIBezierPath bezierPathWithRect:frame]];
        }
    }
    _editOutline.path = all.CGPath;
    _selectedOutline.path = selected.CGPath;
    [CATransaction commit];
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    NSArray<UITouch *> *begin = [[touches allObjects] sortedArrayUsingComparator:^NSComparisonResult(UITouch *a, UITouch *b) {
        return a.timestamp < b.timestamp ? NSOrderedAscending : a.timestamp > b.timestamp ? NSOrderedDescending : NSOrderedSame;
    }];
    for (UITouch *touch in begin) {
        CGPoint point;
        if (![self map:touch allowOutside:NO point:&point]) continue;
        if (_editing && _placing >= 0) {
            for (NSUInteger i = 8; i + 4 < _regions.count; i += 5) {
                if (_regions[i + 4].integerValue == 100 + _placing) {
                    [self.runtime placeControl:_placing atPoint:point];
                    _placing = -1;
                    break;
                }
            }
            return;
        }
        if (_fingers.count == 0) _nextFinger = 0;
        const NSInteger finger = _nextFinger++;
        _fingers[[NSValue valueWithNonretainedObject:touch]] = @(finger);
        [self.runtime touch:0 finger:finger point:point];
    }
}

- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    NSMutableDictionary<NSNumber *, NSValue *> *points = [NSMutableDictionary dictionary];
    for (UITouch *touch in touches) {
        NSNumber *finger = _fingers[[NSValue valueWithNonretainedObject:touch]];
        CGPoint point;
        if (finger && [self map:touch allowOutside:YES point:&point])
            points[finger] = [NSValue valueWithCGPoint:point];
    }
    if (points.count) [self.runtime moveFingers:points];
}

- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    for (UITouch *touch in touches) {
        NSValue *key = [NSValue valueWithNonretainedObject:touch];
        NSNumber *finger = _fingers[key];
        CGPoint point;
        if (finger && [self map:touch allowOutside:YES point:&point])
            [self.runtime touch:2 finger:finger.integerValue point:point];
        [_fingers removeObjectForKey:key];
    }
}

- (void)touchesCancelled:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event { [self cancelTouches]; }

- (void)cancelTouches {
    [_fingers removeAllObjects];
    [self.runtime touch:3 finger:0 point:CGPointZero];
}

@end

@interface NSCGameController : UIViewController <NSCGameDelegate, UITextFieldDelegate>
@end

@implementation NSCGameController {
    NSCGameRuntime *_engine;
    NSCGameCanvas *_canvas;
    UILabel *_status;
    UIButton *_start, *_menu, *_editorAction, *_appearance;
    UITextField *_editor;
    UIStackView *_editorBar;
    UIStackView *_layoutBar;
    CGSize _viewport, _lastFrame;
    NSString *_editorSerial, *_gameState;
    NSArray<NSNumber *> *_regions;
    NSInteger _selectedControl;
    NSInteger _editorTarget, _lastUiRequest;
    BOOL _bindingEditor, _settingsApplied, _editing, _observer;
    CGFloat _interfaceScale, _requestedScale, _cameraSensitivity;
    NSInteger _renderer, _ultraMask;
    BOOL _hdShadows, _graphicsBusy, _restoredRenderer;
    NSInteger _armMillis, _menuMillis;
    UIBackgroundTaskIdentifier _background;
}

- (UIButton *)button:(NSString *)title action:(SEL)action {
    UIButtonConfiguration *configuration = [UIButtonConfiguration tintedButtonConfiguration];
    configuration.title = title;
    configuration.baseForegroundColor = UIColor.systemYellowColor;
    configuration.contentInsets = NSDirectionalEdgeInsetsMake(7, 12, 7, 12);
    configuration.titleTextAttributesTransformer = ^NSDictionary<NSAttributedStringKey,id> *(NSDictionary *attributes) {
        NSMutableDictionary *updated = [attributes mutableCopy];
        updated[NSFontAttributeName] = [[UIFontMetrics metricsForTextStyle:UIFontTextStyleFootnote]
            scaledFontForFont:[UIFont systemFontOfSize:13 weight:UIFontWeightSemibold] maximumPointSize:18];
        return updated;
    };
    UIButton *button = [UIButton buttonWithConfiguration:configuration primaryAction:nil];
    [button.heightAnchor constraintGreaterThanOrEqualToConstant:44].active = YES;
    [button addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    return button;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = UIColor.blackColor;
    self.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
    _background = UIBackgroundTaskInvalid;
    _lastUiRequest = -1;
    _editorSerial = @"";
    _gameState = @"";
    NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
    [defaults registerDefaults:@{@"interfaceScale":@1.0, @"cameraSensitivity":@2.0,
                                  @"cameraArmMs":@180, @"contextMenuMs":@650,
                                  @"rendererMode":@0, @"ultraOptions":@117, @"hdShadows":@YES}];
    _interfaceScale = [defaults doubleForKey:@"interfaceScale"];
    _cameraSensitivity = [defaults doubleForKey:@"cameraSensitivity"];
    _armMillis = [defaults integerForKey:@"cameraArmMs"];
    _menuMillis = [defaults integerForKey:@"contextMenuMs"];
    _observer = [defaults boolForKey:@"botObserver"];
    _renderer = MAX(0, MIN(3, [defaults integerForKey:@"rendererMode"]));
    _ultraMask = [defaults integerForKey:@"ultraOptions"] & 255;
    _hdShadows = [defaults boolForKey:@"hdShadows"];

    _engine = [NSCGameRuntime new];
    _engine.delegate = self;
    _canvas = [NSCGameCanvas new];
    _canvas.runtime = _engine;
    _canvas.translatesAutoresizingMaskIntoConstraints = NO;
    [self.view addSubview:_canvas];
    _status = [UILabel new];
    _status.numberOfLines = 0;
    _status.textAlignment = NSTextAlignmentCenter;
    _status.textColor = UIColor.whiteColor;
    _status.font = [UIFont preferredFontForTextStyle:UIFontTextStyleFootnote];
    _status.adjustsFontForContentSizeCategory = YES;
    _status.text = @"Newscape owner test";
    _start = [self button:@"Connect" action:@selector(connect)];
    UIStackView *startControls = [[UIStackView alloc] initWithArrangedSubviews:@[_status, _start]];
    startControls.axis = UILayoutConstraintAxisVertical;
    startControls.spacing = 8;
    startControls.alignment = UIStackViewAlignmentCenter;
    startControls.translatesAutoresizingMaskIntoConstraints = NO;
    [self.view addSubview:startControls];

    _menu = [self button:@"Menu" action:@selector(showMenu)];
    _menu.translatesAutoresizingMaskIntoConstraints = NO;
    _menu.hidden = YES;
    [self.view addSubview:_menu];

    _editor = [UITextField new];
    _editor.borderStyle = UITextBorderStyleRoundedRect;
    _editor.clearButtonMode = UITextFieldViewModeWhileEditing;
    _editor.autocorrectionType = UITextAutocorrectionTypeNo;
    _editor.autocapitalizationType = UITextAutocapitalizationTypeNone;
    _editor.spellCheckingType = UITextSpellCheckingTypeNo;
    _editor.smartQuotesType = UITextSmartQuotesTypeNo;
    _editor.smartDashesType = UITextSmartDashesTypeNo;
    _editor.textContentType = @"";
    _editor.delegate = self;
    [_editor addTarget:self action:@selector(editorChanged) forControlEvents:UIControlEventEditingChanged];
    _editorAction = [self button:@"Send" action:@selector(submitEditor)];
    _editorBar = [[UIStackView alloc] initWithArrangedSubviews:@[_editor, _editorAction, [self button:@"Hide" action:@selector(dismissEditor)]]];
    _editorBar.spacing = 6;
    _editorBar.axis = UILayoutConstraintAxisHorizontal;
    _editorBar.alignment = UIStackViewAlignmentCenter;
    _editorBar.translatesAutoresizingMaskIntoConstraints = NO;
    _editorBar.hidden = YES;
    [self.view addSubview:_editorBar];

    _appearance = [self button:@"Appearance" action:@selector(controlAppearance)];
    _appearance.enabled = NO;
    _layoutBar = [[UIStackView alloc] initWithArrangedSubviews:@[
        [self button:@"Place" action:@selector(chooseControl)],
        _appearance, [self button:@"Save" action:@selector(saveLayout)],
        [self button:@"Cancel" action:@selector(cancelLayout)]
    ]];
    _layoutBar.spacing = 6;
    _layoutBar.translatesAutoresizingMaskIntoConstraints = NO;
    _layoutBar.hidden = YES;
    [self.view addSubview:_layoutBar];

    UILayoutGuide *safe = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [_canvas.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor],
        [_canvas.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor],
        [_canvas.topAnchor constraintEqualToAnchor:safe.topAnchor],
        [_canvas.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor],
        [startControls.centerXAnchor constraintEqualToAnchor:safe.centerXAnchor],
        [startControls.centerYAnchor constraintEqualToAnchor:safe.centerYAnchor],
        [startControls.widthAnchor constraintLessThanOrEqualToAnchor:safe.widthAnchor multiplier:0.9],
        [_menu.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:6],
        [_menu.topAnchor constraintEqualToAnchor:safe.topAnchor constant:3],
        [_editorBar.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:8],
        [_editorBar.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-8],
        [_editorBar.bottomAnchor constraintEqualToAnchor:self.view.keyboardLayoutGuide.topAnchor constant:-4],
        [_layoutBar.centerXAnchor constraintEqualToAnchor:safe.centerXAnchor],
        [_layoutBar.topAnchor constraintEqualToAnchor:safe.topAnchor constant:5]
    ]];
    NSNotificationCenter *notifications = NSNotificationCenter.defaultCenter;
    [notifications addObserver:self selector:@selector(becameActive) name:UIApplicationDidBecomeActiveNotification object:nil];
    [notifications addObserver:self selector:@selector(willResign) name:UIApplicationWillResignActiveNotification object:nil];
    [notifications addObserver:self selector:@selector(backgrounded) name:UIApplicationDidEnterBackgroundNotification object:nil];
}

- (BOOL)prefersStatusBarHidden { return YES; }

- (void)connect {
    _start.hidden = YES;
    _status.text = @"Starting Newscape";
    [_engine start];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self updateViewport];
}

- (void)updateViewport {
    CGSize screen = _canvas.bounds.size;
    if (screen.width <= 0 || screen.height <= 0 || !_engine.running) return;
    if (!CGSizeEqualToSize(_viewport, screen) || _interfaceScale != _requestedScale) {
        _viewport = screen;
        _requestedScale = _interfaceScale;
        [_engine setViewport:screen scale:_interfaceScale];
    }
    if (_lastFrame.width > 0)
        [_engine setCanvas:_lastFrame gestureThreshold:8 * _lastFrame.width / screen.width];
}

- (void)applyGestureSettings {
    [_engine setArmMillis:_armMillis menuMillis:_menuMillis sensitivity:_cameraSensitivity observer:_observer];
}

- (void)engineStatus:(NSString *)status {
    _gameState = [status componentsSeparatedByString:@" · "].firstObject ?: @"";
    _status.text = status;
    if (!_engine.running && !_canvas.layer.contents) _start.hidden = NO;
    if (!_restoredRenderer && [_gameState isEqualToString:@"LOGGED_IN"]) {
        _restoredRenderer = YES;
        if (_renderer != 0) [self requestRenderer:_renderer];
    }
}

- (void)engineFrame:(CGImageRef)frame width:(NSInteger)width height:(NSInteger)height {
    BOOL first = _lastFrame.width == 0;
    _canvas.layer.contents = (__bridge id)frame;
    if (_lastFrame.width != width || _lastFrame.height != height) {
        _lastFrame = CGSizeMake(width, height);
        _canvas.frameSize = _lastFrame;
        [self updateViewport];
    }
    if (first) {
        _status.hidden = YES;
        _menu.hidden = NO;
        [self applyGestureSettings];
    }
}

- (void)engineEditor:(NSString *)response regions:(NSArray<NSNumber *> *)regions layoutSelection:(NSInteger)selection {
    _regions = regions;
    _selectedControl = selection;
    if (_editing) {
        _canvas.regions = regions;
        _canvas.selection = selection;
        _appearance.enabled = [self canStyle:selection];
    }
    if (!_settingsApplied && _engine.running) {
        _settingsApplied = YES;
        [self updateViewport];
        [self applyGestureSettings];
    }
    if (regions.count >= 8 && [regions[1] integerValue] == 30) {
        _menu.hidden = _editing || [regions[0] integerValue] == 1;
        NSInteger request = [regions[2] integerValue];
        if (request != _lastUiRequest) {
            _lastUiRequest = request;
            NSInteger action = [regions[3] integerValue];
            if (action == 1) [self showMenu];
            else if (action == 2) [self chooseHotkey:[regions[4] integerValue]];
        }
    }
    NSRange first = [response rangeOfString:@"\n"];
    if (first.location == NSNotFound) return;
    NSUInteger suffix = NSMaxRange(first);
    NSRange second = [response rangeOfString:@"\n" options:0 range:NSMakeRange(suffix, response.length-suffix)];
    if (second.location == NSNotFound) return;
    NSString *serial = [response substringToIndex:first.location];
    if ([_editorSerial isEqualToString:serial]) return;
    _editorSerial = serial;
    NSInteger target = [[response substringWithRange:NSMakeRange(suffix, second.location-suffix)] integerValue];
    if (!target) { [self closeEditor:NO]; return; }
    [self openEditor:target text:[response substringFromIndex:NSMaxRange(second)]];
}

- (void)openEditor:(NSInteger)target text:(NSString *)text {
    _bindingEditor = YES;
    _editorTarget = target;
    _editor.secureTextEntry = target == 2;
    _editor.returnKeyType = target == 1 ? UIReturnKeyNext : target == 2 ? UIReturnKeyGo : UIReturnKeySend;
    _editor.placeholder = target == 1 ? @"Username" : target == 2 ? @"Password" : target == 3 ? @"Chat" : @"Enter text";
    _editor.text = text;
    UIButtonConfiguration *action = _editorAction.configuration;
    action.title = target == 1 ? @"Next" : target == 2 ? @"Log in" : @"Send";
    _editorAction.configuration = action;
    _editorBar.hidden = NO;
    _bindingEditor = NO;
    [_editor becomeFirstResponder];
}

- (void)editorChanged {
    if (!_bindingEditor && _editorTarget)
        [_engine event:9 x:0 y:0 value:_editorTarget text:_editor.text];
}

- (BOOL)textField:(UITextField *)field shouldChangeCharactersInRange:(NSRange)range replacementString:(NSString *)value {
    NSUInteger limit = _editorTarget == 1 ? 320 : _editorTarget == 2 ? 20 : 80;
    if (NSMaxRange(range) > field.text.length || field.text.length - range.length + value.length > limit) return NO;
    for (NSUInteger i = 0; i < value.length; i++) {
        unichar ch = [value characterAtIndex:i];
        if (ch < 32 || ch > 126) return NO;
    }
    return YES;
}

- (BOOL)textFieldShouldReturn:(UITextField *)field { [self submitEditor]; return NO; }

- (void)submitEditor {
    if (!_editorTarget) return;
    [self editorChanged];
    if (_editorTarget == 1) { [_engine event:12 x:0 y:0 value:2 text:nil]; return; }
    [_engine event:10 x:0 y:0 value:10 text:nil];
    [self closeEditor:YES];
}

- (void)dismissEditor { [self closeEditor:YES]; }

- (void)closeEditor:(BOOL)notify {
    if (!_editorTarget) return;
    if (notify) [_engine event:11 x:0 y:0 value:0 text:nil];
    _bindingEditor = YES;
    _editorTarget = 0;
    [_editor resignFirstResponder];
    _editor.text = @"";
    _editorBar.hidden = YES;
    _bindingEditor = NO;
}

- (void)choose:(NSString *)title actions:(NSArray<NSString *> *)choices selected:(void (^)(NSUInteger))callback {
    if (self.presentedViewController || !self.view.window) return;
    UIAlertController *menu = [UIAlertController alertControllerWithTitle:title message:nil
        preferredStyle:UIAlertControllerStyleAlert];
    __weak UIAlertController *closing = menu;
    for (NSUInteger index = 0; index < choices.count; index++)
        [menu addAction:[UIAlertAction actionWithTitle:choices[index] style:UIAlertActionStyleDefault
            handler:^(__unused UIAlertAction *selected) {
            [self afterMenu:closing remaining:20 selected:^{ callback(index); }];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:menu animated:YES completion:nil];
}

- (void)afterMenu:(UIAlertController *)menu remaining:(NSInteger)remaining selected:(dispatch_block_t)selected {
    if (!self.view.window || remaining <= 0) return;
    if (!self.presentedViewController) { selected(); return; }
    if (self.presentedViewController != menu) return;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 50 * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
        [self afterMenu:menu remaining:remaining-1 selected:selected];
    });
}

- (void)showMenu {
    if (!_engine.running || _editing) return;
    [_canvas cancelTouches];
    [self choose:@"Newscape" actions:@[@"RuneLite plugins", @"Developer and desktop panels", @"Camera gestures",
        @"Bot observer camera", @"Interface size", @"Game layout", @"Customize hotkeys",
        @"Edit layout", @"Keyboard", @"Escape", @"Connection status", @"Graphics"]
        selected:^(NSUInteger choice) {
        switch (choice) {
            case 0: [self showPlugins:NO]; break;
            case 1: [self showPlugins:YES]; break;
            case 2: [self cameraSettings]; break;
            case 3: [self toggleObserver]; break;
            case 4: [self interfaceSize]; break;
            case 5: [self gameLayout]; break;
            case 6: {
                [self choose:@"Hotkey" actions:@[@"Hotkey 1", @"Hotkey 2", @"Hotkey 3", @"Hotkey 4", @"Hotkey 5"]
                     selected:^(NSUInteger slot) { [self chooseHotkey:slot]; }];
                break;
            }
            case 7: [self startEditing]; break;
            case 8: [self->_engine event:12 x:0 y:0 value:[self->_gameState isEqualToString:@"LOGIN_SCREEN"] ? 1 : 3 text:nil]; break;
            case 9: [self->_engine event:10 x:0 y:0 value:27 text:nil]; break;
            case 10: [self showStatus]; break;
            case 11: [self graphicsMenu]; break;
        }
    }];
}

- (void)graphicsMenu {
    [self choose:@"Graphics" actions:@[@"Classic", @"Newscape HD", @"Newscape Ultra HD", @"117 HD",
        @"HD shadows", @"Ultra quality"] selected:^(NSUInteger index) {
        if (index < 4) [self requestRenderer:index];
        else if (index == 4) [self shadowMenu];
        else [self ultraMenu];
    }];
}

- (void)renderError:(NSString *)message {
    if (!self.view.window || self.presentedViewController) return;
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:@"Graphics"
        message:message preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:alert animated:YES completion:nil];
}

- (void)requestRenderer:(NSInteger)mode {
    if (_graphicsBusy) return;
    if (mode != 0 && ![_gameState isEqualToString:@"LOGGED_IN"]) {
        [self renderError:@"Log in before switching to an HD renderer."];
        return;
    }
    _graphicsBusy = YES;
    [_engine plugin:@{@"op":@"list"} completion:^(NSDictionary *reply) {
        if (![reply[@"ok"] boolValue] || [reply[@"busy"] boolValue]) {
            self->_graphicsBusy = NO;
            [self renderError:@"Wait for the current graphics transition to finish."];
            return;
        }
        NSDictionary *plugin = nil;
        if ([reply[@"plugins"] isKindOfClass:NSArray.class]) {
            for (NSDictionary *entry in reply[@"plugins"]) {
                if ([entry isKindOfClass:NSDictionary.class] && [entry[@"id"] isEqual:@"rs117.hd.HdPlugin"]) {
                    plugin = entry;
                    break;
                }
            }
        }
        const BOOL wants117 = mode == 3;
        const BOOL running117 = [reply[@"hdRunning"] boolValue];
        const BOOL configured117 = [plugin[@"enabled"] boolValue];
        if (wants117 && !plugin) {
            self->_graphicsBusy = NO;
            [self renderError:@"117 HD is unavailable in the installed client runtime."];
            return;
        }
        if (wants117 && running117) {
            [self completeRenderer:mode];
            return;
        }
        if (!wants117 && !(running117 || configured117)) {
            if ([reply[@"rendererReady"] boolValue]) [self completeRenderer:mode];
            else [self pollRenderer:mode until:CACurrentMediaTime() + 8.0];
            return;
        }
        [self->_engine plugin:@{@"op":@"toggle", @"plugin":@"rs117.hd.HdPlugin", @"enabled":@(wants117)}
              completion:^(NSDictionary *change) {
            if (![change[@"ok"] boolValue]) {
                self->_graphicsBusy = NO;
                [self renderError:[change[@"error"] isKindOfClass:NSString.class] ? change[@"error"] :
                                  @"The 117 HD transition could not start."];
                return;
            }
            [self pollRenderer:mode until:CACurrentMediaTime() + 8.0];
        }];
    }];
}

- (void)pollRenderer:(NSInteger)mode until:(CFTimeInterval)deadline {
    if (CACurrentMediaTime() >= deadline) {
        _graphicsBusy = NO;
        [self renderError:@"The previous renderer did not finish its transition."];
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 350 * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
        [self->_engine plugin:@{@"op":@"list"} completion:^(NSDictionary *reply) {
            if ([reply[@"ok"] boolValue] && ![reply[@"busy"] boolValue]) {
                BOOL ready = mode == 3 ? [reply[@"hdRunning"] boolValue] : [reply[@"rendererReady"] boolValue];
                if (ready) { [self completeRenderer:mode]; return; }
                NSString *detail = [reply[@"lastError"] isKindOfClass:NSString.class] ? reply[@"lastError"] : @"";
                if (detail.length) {
                    self->_graphicsBusy = NO;
                    [self renderError:detail];
                    return;
                }
            }
            [self pollRenderer:mode until:deadline];
        }];
    });
}

- (void)completeRenderer:(NSInteger)mode {
    _graphicsBusy = NO;
    _renderer = mode;
    [NSUserDefaults.standardUserDefaults setInteger:mode forKey:@"rendererMode"];
    // The real 117 HD plugin bridge already released Mobile HD before starting its own renderer.
    if (mode != 3) [self applyMobileGraphics];
}

- (void)applyMobileGraphics {
    if (_renderer != 3)
        [_engine setMobileHd:_renderer != 0 ultraMask:_renderer == 2 ? _ultraMask : 0 shadows:_hdShadows];
}

- (void)shadowMenu {
    [self choose:@"HD shadows" actions:@[@"Off", @"On"] selected:^(NSUInteger index) {
        self->_hdShadows = index != 0;
        [NSUserDefaults.standardUserDefaults setBool:self->_hdShadows forKey:@"hdShadows"];
        [self applyMobileGraphics];
    }];
}

- (void)ultraMenu {
    [self choose:@"Ultra quality" actions:@[@"Performance", @"Balanced", @"High", @"Ultra", @"Custom"] selected:^(NSUInteger index) {
        static const NSInteger masks[] = {80, 117, 254, 255};
        if (index < 4) [self saveUltraMask:masks[index]];
        else [self customUltra];
    }];
}

- (void)customUltra {
    NSArray *features = @[@"Contact occlusion", @"Edge antialiasing", @"Bloom",
                         @"Material highlights", @"Distance fog", @"Soft shadow filtering"];
    const NSInteger bits[] = {3, 4, 8, 16, 32, 128};
    NSMutableArray<NSString *> *labels = [NSMutableArray array];
    for (NSUInteger index = 0; index < features.count; index++)
        [labels addObject:[NSString stringWithFormat:@"%@   %@", features[index], (_ultraMask & bits[index]) ? @"On" : @"Off"]];
    [self choose:@"Custom Ultra quality" actions:labels selected:^(NSUInteger index) {
        static const NSInteger selections[] = {3, 4, 8, 16, 32, 128};
        NSInteger bit = selections[index];
        [self saveUltraMask:(self->_ultraMask & bit) != 0 ? self->_ultraMask & ~bit : self->_ultraMask | bit];
    }];
}

- (void)saveUltraMask:(NSInteger)mask {
    _ultraMask = mask;
    [NSUserDefaults.standardUserDefaults setInteger:mask forKey:@"ultraOptions"];
    [self applyMobileGraphics];
}

- (void)showStatus {
    UIAlertController *status = [UIAlertController alertControllerWithTitle:@"Connection status"
        message:_status.text preferredStyle:UIAlertControllerStyleAlert];
    [status addAction:[UIAlertAction actionWithTitle:@"Done" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:status animated:YES completion:nil];
}

- (void)showPlugins:(BOOL)desktop {
    NSCPluginListController *list = [[NSCPluginListController alloc] initWithRuntime:_engine desktop:desktop];
    UINavigationController *nav = [[UINavigationController alloc] initWithRootViewController:list];
    nav.navigationBar.prefersLargeTitles = NO;
    nav.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:nav animated:YES completion:nil];
}

- (void)toggleObserver {
    _observer = !_observer;
    [NSUserDefaults.standardUserDefaults setBool:_observer forKey:@"botObserver"];
    [self applyGestureSettings];
}

- (void)cameraSettings {
    NSArray<NSString *> *fields = @[@"Camera hold in ms", @"Context menu hold in ms", @"Camera sensitivity"];
    NSArray<NSNumber *> *values = @[@(_armMillis), @(_menuMillis), @(_cameraSensitivity)];
    UIAlertController *dialog = [UIAlertController alertControllerWithTitle:@"Camera gestures"
        message:@"Set hold times and sensitivity" preferredStyle:UIAlertControllerStyleAlert];
    for (NSUInteger i = 0; i < fields.count; i++)
        [dialog addTextFieldWithConfigurationHandler:^(UITextField *input) {
            input.placeholder = fields[i];
            input.text = values[i].stringValue;
            input.keyboardType = i < 2 ? UIKeyboardTypeNumberPad : UIKeyboardTypeDecimalPad;
        }];
    __weak UIAlertController *weakDialog = dialog;
    [dialog addAction:[UIAlertAction actionWithTitle:@"Apply" style:UIAlertActionStyleDefault
        handler:^(__unused UIAlertAction *selected) {
        NSArray<UITextField *> *inputs = weakDialog.textFields;
        if (inputs.count != 3) return;
        self->_armMillis = MAX(80, MIN(400, inputs[0].text.integerValue));
        self->_menuMillis = MAX(self->_armMillis + 150, MIN(1500, inputs[1].text.integerValue));
        self->_cameraSensitivity = MAX(0.25, MIN(5, inputs[2].text.doubleValue));
        NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
        [defaults setInteger:self->_armMillis forKey:@"cameraArmMs"];
        [defaults setInteger:self->_menuMillis forKey:@"contextMenuMs"];
        [defaults setDouble:self->_cameraSensitivity forKey:@"cameraSensitivity"];
        [self applyGestureSettings];
    }]];
    [dialog addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:dialog animated:YES completion:nil];
}

- (void)interfaceSize {
    [self choose:@"Interface size" actions:@[@"Compact", @"Medium", @"Standard"] selected:^(NSUInteger size) {
        self->_interfaceScale = size == 0 ? 0.8 : size == 1 ? 0.9 : 1;
        [NSUserDefaults.standardUserDefaults setDouble:self->_interfaceScale forKey:@"interfaceScale"];
        [self updateViewport];
    }];
}

- (void)gameLayout {
    [self choose:@"Game layout" actions:@[@"Mobile layout", @"Classic layout"] selected:^(NSUInteger layout) {
        [self->_engine event:15 x:0 y:0 value:layout == 0 ? 1 : 0 text:nil];
    }];
}

- (void)chooseHotkey:(NSInteger)slot {
    if (slot < 0 || slot >= 5) return;
    NSArray *tabs = @[@"Combat", @"Skills", @"Quests", @"Inventory", @"Equipment", @"Prayer", @"Spellbook",
        @"Clan chat", @"Account", @"Friends", @"Logout", @"Settings", @"Emotes", @"Music"];
    [self choose:[NSString stringWithFormat:@"Hotkey %ld", (long)slot + 1] actions:tabs selected:^(NSUInteger tab) {
        [self->_engine event:15 x:10+slot y:0 value:tab text:nil];
    }];
}

- (void)startEditing {
    if (_regions.count < 8 || _regions[0].integerValue != 1) {
        UIAlertController *alert = [UIAlertController alertControllerWithTitle:@"Edit layout"
            message:@"Log in and select Mobile layout first." preferredStyle:UIAlertControllerStyleAlert];
        [alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleCancel handler:nil]];
        [self presentViewController:alert animated:YES completion:nil];
        return;
    }
    [_canvas cancelTouches];
    [self closeEditor:YES];
    _editing = YES;
    _canvas.editing = YES;
    _canvas.regions = _regions;
    _layoutBar.hidden = NO;
    _menu.hidden = YES;
    [_engine setLayoutEditing:YES save:NO];
}

- (void)endEditing:(BOOL)save {
    [_canvas cancelTouches];
    [_engine setLayoutEditing:NO save:save];
    _layoutBar.hidden = YES;
    _menu.hidden = NO;
    _editing = NO;
    _canvas.editing = NO;
}
- (BOOL)canStyle:(NSInteger)control {
    return (control >= 0 && control <= 18) || (control >= 22 && control <= 26);
}

- (NSString *)layoutName:(NSInteger)control {
    NSArray *tabs = @[@"Combat", @"Skills", @"Quests", @"Inventory", @"Equipment", @"Prayer", @"Spellbook",
        @"Clan chat", @"Account", @"Friends", @"Logout", @"Settings", @"Emotes", @"Music"];
    if (control >= 0 && control < 14) return [tabs[control] stringByAppendingString:@" button"];
    if (control >= 14 && control <= 18) return [NSString stringWithFormat:@"Hotkey %ld", (long)control - 13];
    if (control >= 40 && control < 54) return [tabs[control - 40] stringByAppendingString:@" panel"];
    NSDictionary *special = @{@20:@"Minimap", @21:@"Chat panel", @22:@"Tab collapse", @23:@"Menu",
        @24:@"Chat button", @25:@"Keyboard", @26:@"Logout button"};
    return special[@(control)] ?: @"Control";
}

- (void)chooseControl {
    NSMutableArray<NSString *> *names = [NSMutableArray array];
    NSMutableArray<NSNumber *> *ids = [NSMutableArray array];
    for (NSInteger tab = 0; tab < 14; tab++) {
        NSInteger button = tab == 10 ? 26 : tab;
        [ids addObject:@(button)];
        [names addObject:[self layoutName:button]];
        [ids addObject:@(40 + tab)];
        [names addObject:[self layoutName:40 + tab]];
    }
    [self choose:@"Choose a control, then tap its new position" actions:names selected:^(NSUInteger choice) {
        NSInteger target = ids[choice].integerValue;
        if (target >= 40) [self->_engine event:15 x:3 y:0 value:target-40 text:nil];
        [self->_canvas cancelTouches];
        self->_canvas.placing = target;
    }];
}

- (void)controlAppearance {
    NSInteger control = _selectedControl;
    if (![self canStyle:control]) return;
    NSInteger size = 100, opacity = 100;
    for (NSUInteger i = 8; i + 4 < _regions.count; i += 5)
        if (_regions[i + 4].integerValue == 200 + control) {
            size = _regions[i].integerValue;
            opacity = _regions[i+1].integerValue;
            break;
        }
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:[self layoutName:control]
        message:@"Size 60–180%. Opacity 10–100%." preferredStyle:UIAlertControllerStyleAlert];
    [alert addTextFieldWithConfigurationHandler:^(UITextField *field) {
        field.text = @(size).stringValue; field.placeholder = @"Size"; field.keyboardType = UIKeyboardTypeNumberPad;
    }];
    [alert addTextFieldWithConfigurationHandler:^(UITextField *field) {
        field.text = @(opacity).stringValue; field.placeholder = @"Opacity"; field.keyboardType = UIKeyboardTypeNumberPad;
    }];
    __weak UIAlertController *weakAlert = alert;
    [alert addAction:[UIAlertAction actionWithTitle:@"Apply" style:UIAlertActionStyleDefault
        handler:^(__unused UIAlertAction *action) {
        NSArray *fields = weakAlert.textFields;
        if (fields.count != 2) return;
        UITextField *sizeField = fields[0], *opacityField = fields[1];
        [self->_engine event:17 x:MAX(60, MIN(180, sizeField.text.integerValue))
                            y:MAX(10, MIN(100, opacityField.text.integerValue)) value:control text:nil];
    }]];
    [alert addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:alert animated:YES completion:nil];
}

- (void)saveLayout { [self endEditing:YES]; }
- (void)cancelLayout { [self endEditing:NO]; }

- (void)becameActive {
    [self endBackground];
    _editorBar.hidden = !_editorTarget;
    [_engine setVisible:YES];
    [self updateViewport];
}

- (void)willResign {
    if (_editorTarget == 2) _editorBar.hidden = YES;
    [_canvas cancelTouches];
    [_engine event:19 x:0 y:0 value:0 text:nil];
    [_engine setVisible:NO];
}

- (void)backgrounded {
    if (!_engine.running || _background != UIBackgroundTaskInvalid) return;
    __weak typeof(self) weakSelf = self;
    _background = [UIApplication.sharedApplication beginBackgroundTaskWithName:@"Finish game transition"
        expirationHandler:^{ [weakSelf endBackground]; }];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        [weakSelf endBackground];
    });
}

- (void)endBackground {
    if (_background == UIBackgroundTaskInvalid) return;
    UIBackgroundTaskIdentifier task = _background;
    _background = UIBackgroundTaskInvalid;
    [UIApplication.sharedApplication endBackgroundTask:task];
}

@end

@interface NSCAppDelegate : UIResponder <UIApplicationDelegate>
@property (nonatomic) UIWindow *window;
@end

@implementation NSCAppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [NSCGameController new];
    [self.window makeKeyAndVisible];
    return YES;
}

@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(NSCAppDelegate.class));
    }
}
