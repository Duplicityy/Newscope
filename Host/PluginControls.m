#import "PluginControls.h"
#import <ImageIO/ImageIO.h>

static NSString *Text(id value) {
    if ([value isKindOfClass:NSString.class]) return value;
    if ([value isKindOfClass:NSNumber.class]) return [value stringValue];
    return @"";
}

static NSArray<NSDictionary *> *Rows(id value) {
    if (![value isKindOfClass:NSArray.class]) return @[];
    NSMutableArray *items = [NSMutableArray array];
    for (id item in value) if ([item isKindOfClass:NSDictionary.class]) [items addObject:item];
    return items;
}

static void CompactLabel(UILabel *label, CGFloat size, UIFontWeight weight) {
    label.font = [[UIFontMetrics metricsForTextStyle:UIFontTextStyleFootnote]
        scaledFontForFont:[UIFont systemFontOfSize:size weight:weight] maximumPointSize:size + 5];
    label.adjustsFontForContentSizeCategory = YES;
    label.numberOfLines = 0;
}

static UITableViewCell *Cell(NSString *title, NSString *detail) {
    UITableViewCell *row = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:nil];
    CompactLabel(row.textLabel, 14, UIFontWeightMedium);
    CompactLabel(row.detailTextLabel, 11, UIFontWeightRegular);
    row.textLabel.text = title;
    row.detailTextLabel.text = detail;
    return row;
}

static void ShowError(UIViewController *owner, NSString *message) {
    if (!owner.view.window || owner.presentedViewController) return;
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:@"Plugin controls"
        message:message.length ? message : @"The plugin request could not finish."
        preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleCancel handler:nil]];
    [owner presentViewController:alert animated:YES completion:nil];
}

static UIStackView *Vertical(void) {
    UIStackView *stack = [UIStackView new];
    stack.axis = UILayoutConstraintAxisVertical;
    stack.spacing = 6;
    return stack;
}

@interface NSCPluginConfigController : UITableViewController
- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime plugin:(NSDictionary *)plugin;
@end

@interface NSCDesktopPanelController : UIViewController
- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime panel:(NSDictionary *)panel;
@end

@interface NSCPluginListController () <UISearchResultsUpdating>
@end

@implementation NSCPluginListController {
    NSCGameRuntime *_runtime;
    BOOL _desktop;
    NSArray<NSDictionary *> *_items, *_filtered;
}

- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime desktop:(BOOL)desktop {
    if ((self = [super initWithStyle:UITableViewStyleInsetGrouped])) {
        _runtime = runtime;
        _desktop = desktop;
        _items = _filtered = @[];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = _desktop ? @"Developer and desktop panels" : @"RuneLite plugins";
    self.navigationItem.largeTitleDisplayMode = UINavigationItemLargeTitleDisplayModeNever;
    self.navigationItem.leftBarButtonItem = [[UIBarButtonItem alloc] initWithTitle:@"Back to game"
        style:UIBarButtonItemStylePlain target:self action:@selector(close)];
    self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc] initWithBarButtonSystemItem:UIBarButtonSystemItemRefresh
        target:self action:@selector(loadItems)];
    self.tableView.estimatedRowHeight = 48;
    self.tableView.rowHeight = UITableViewAutomaticDimension;
    UISearchController *search = [[UISearchController alloc] initWithSearchResultsController:nil];
    search.obscuresBackgroundDuringPresentation = NO;
    search.searchResultsUpdater = self;
    search.searchBar.placeholder = _desktop ? @"Search panels" : @"Search plugins";
    search.searchBar.searchTextField.font = [[UIFontMetrics metricsForTextStyle:UIFontTextStyleFootnote]
        scaledFontForFont:[UIFont systemFontOfSize:13] maximumPointSize:18];
    self.navigationItem.searchController = search;
    self.definesPresentationContext = YES;
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self loadItems];
}

- (void)close { [self dismissViewControllerAnimated:YES completion:nil]; }

- (void)loadItems {
    __weak typeof(self) weakSelf = self;
    [_runtime plugin:@{@"op": _desktop ? @"panelList" : @"list"} completion:^(NSDictionary *reply) {
        typeof(self) self = weakSelf;
        if (!self || !self.view.window) return;
        if (![reply[@"ok"] boolValue]) { ShowError(self, Text(reply[@"error"])); return; }
        NSArray *items = Rows(reply[self->_desktop ? @"panels" : @"plugins"]);
        self->_items = [items sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
            return [Text(a[@"name"]) localizedCaseInsensitiveCompare:Text(b[@"name"])];
        }];
        [self updateSearchResultsForSearchController:self.navigationItem.searchController];
    }];
}

- (void)updateSearchResultsForSearchController:(UISearchController *)search {
    NSString *query = [search.searchBar.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (!query.length) _filtered = _items;
    else {
        _filtered = [_items filteredArrayUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(NSDictionary *row, NSDictionary *bindings) {
            return [[NSString stringWithFormat:@"%@ %@", Text(row[@"name"]), Text(row[@"description"])]
                localizedCaseInsensitiveContainsString:query];
        }]];
    }
    [self.tableView reloadData];
}

- (NSInteger)tableView:(UITableView *)table numberOfRowsInSection:(NSInteger)section { return _filtered.count; }

- (UITableViewCell *)tableView:(UITableView *)table cellForRowAtIndexPath:(NSIndexPath *)path {
    NSDictionary *item = _filtered[path.row];
    NSString *detail = _desktop ? Text(item[@"description"]) : Text(item[@"blockedReason"]);
    if (!_desktop && !detail.length)
        detail = [item[@"running"] boolValue] ? @"Running" : [item[@"enabled"] boolValue] ? @"Enabled" : @"Disabled";
    UITableViewCell *cell = Cell(Text(item[@"name"]), detail);
    cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
    return cell;
}

- (void)tableView:(UITableView *)table didSelectRowAtIndexPath:(NSIndexPath *)path {
    NSDictionary *item = _filtered[path.row];
    UIViewController *next = _desktop ? [[NSCDesktopPanelController alloc] initWithRuntime:_runtime panel:item]
                                     : [[NSCPluginConfigController alloc] initWithRuntime:_runtime plugin:item];
    [self.navigationController pushViewController:next animated:YES];
}

@end

@implementation NSCPluginConfigController {
    NSCGameRuntime *_runtime;
    NSDictionary *_plugin;
    NSArray<NSDictionary *> *_items;
    BOOL _busy, _enabled;
}

- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime plugin:(NSDictionary *)plugin {
    if ((self = [super initWithStyle:UITableViewStyleInsetGrouped])) {
        _runtime = runtime; _plugin = [plugin copy]; _items = @[];
        _enabled = [plugin[@"enabled"] boolValue];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = Text(_plugin[@"name"]);
    self.navigationItem.largeTitleDisplayMode = UINavigationItemLargeTitleDisplayModeNever;
    self.tableView.rowHeight = UITableViewAutomaticDimension;
    self.tableView.estimatedRowHeight = 50;
    [self reloadConfig];
}

- (void)reloadConfig {
    if (![_plugin[@"configurable"] boolValue]) return;
    __weak typeof(self) weakSelf = self;
    [_runtime plugin:@{@"op": @"config", @"plugin": Text(_plugin[@"id"])} completion:^(NSDictionary *reply) {
        typeof(self) self = weakSelf;
        if (!self) return;
        if (![reply[@"ok"] boolValue]) { ShowError(self, Text(reply[@"error"])); return; }
        self->_items = Rows(reply[@"items"]);
        [self.tableView reloadData];
    }];
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)table { return 2; }

- (NSInteger)tableView:(UITableView *)table numberOfRowsInSection:(NSInteger)section {
    return section == 0 ? 1 : _items.count;
}

- (NSString *)tableView:(UITableView *)table titleForHeaderInSection:(NSInteger)section {
    return section == 1 && _items.count ? @"Settings" : nil;
}

- (UITableViewCell *)tableView:(UITableView *)table cellForRowAtIndexPath:(NSIndexPath *)path {
    if (path.section == 0) {
        NSString *blocked = Text(_plugin[@"blockedReason"]);
        UITableViewCell *row = Cell(@"Enabled", blocked.length ? blocked : Text(_plugin[@"description"]));
        UISwitch *control = [UISwitch new];
        control.on = _enabled;
        control.enabled = !blocked.length && !_busy;
        [control addTarget:self action:@selector(toggle:) forControlEvents:UIControlEventValueChanged];
        row.accessoryView = control;
        row.selectionStyle = UITableViewCellSelectionStyleNone;
        return row;
    }
    NSDictionary *item = _items[path.row];
    NSString *description = Text(item[@"description"]);
    NSString *value = Text(item[@"value"]);
    BOOL readOnly = [item[@"readOnly"] boolValue] || [Text(item[@"type"]) isEqualToString:@"unsupported"];
    NSString *reason = Text(item[@"readOnlyReason"]);
    NSMutableArray<NSString *> *details = [NSMutableArray array];
    if (value.length) [details addObject:value];
    if (description.length) [details addObject:description];
    if (readOnly && reason.length) [details addObject:reason];
    UITableViewCell *row = Cell(Text(item[@"name"]), [details componentsJoinedByString:@"\n"]);
    row.accessoryType = readOnly ? UITableViewCellAccessoryNone : UITableViewCellAccessoryDisclosureIndicator;
    row.selectionStyle = readOnly ? UITableViewCellSelectionStyleNone : UITableViewCellSelectionStyleDefault;
    return row;
}

- (void)toggle:(UISwitch *)control {
    if (_busy) return;
    _busy = YES;
    control.enabled = NO;
    const BOOL requested = control.on;
    __weak typeof(self) weakSelf = self;
    [_runtime plugin:@{@"op": @"toggle", @"plugin": Text(_plugin[@"id"]), @"enabled": @(requested)}
         completion:^(NSDictionary *reply) {
        typeof(self) self = weakSelf;
        if (!self) return;
        self->_busy = NO;
        if ([reply[@"ok"] boolValue]) self->_enabled = requested;
        else ShowError(self, Text(reply[@"error"]));
        [self.tableView reloadData];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 650 * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
            [self syncToggleState];
            [self reloadConfig];
        });
    }];
}

- (void)syncToggleState {
    if (!self.view.window) return;
    [_runtime plugin:@{@"op": @"list"} completion:^(NSDictionary *reply) {
        if (![reply[@"ok"] boolValue]) return;
        for (NSDictionary *item in Rows(reply[@"plugins"])) {
            if ([Text(item[@"id"]) isEqualToString:Text(self->_plugin[@"id"])]) {
                self->_plugin = [item copy];
                self->_enabled = [item[@"enabled"] boolValue];
                [self.tableView reloadData];
                break;
            }
        }
    }];
}

- (void)setValue:(NSString *)value forItem:(NSDictionary *)item {
    if (_busy) return;
    void (^apply)(void) = ^{
        self->_busy = YES;
        [self->_runtime plugin:@{@"op": @"set", @"plugin": Text(self->_plugin[@"id"]),
             @"key": Text(item[@"key"]), @"value": value ?: @""}
             completion:^(NSDictionary *reply) {
            self->_busy = NO;
            if (![reply[@"ok"] boolValue]) ShowError(self, Text(reply[@"error"]));
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 400 * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{
                if (self.view.window) [self reloadConfig];
            });
        }];
    };
    NSString *warning = Text(item[@"warning"]);
    if (!warning.length) { apply(); return; }
    UIAlertController *confirmation = [UIAlertController alertControllerWithTitle:Text(item[@"name"])
        message:warning preferredStyle:UIAlertControllerStyleAlert];
    [confirmation addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [confirmation addAction:[UIAlertAction actionWithTitle:@"Apply" style:UIAlertActionStyleDefault handler:^(__unused UIAlertAction *action) { apply(); }]];
    [self presentViewController:confirmation animated:YES completion:nil];
}

- (void)tableView:(UITableView *)table didSelectRowAtIndexPath:(NSIndexPath *)path {
    [table deselectRowAtIndexPath:path animated:YES];
    if (!path.section || _busy) return;
    NSDictionary *item = _items[path.row];
    NSString *type = Text(item[@"type"]);
    if ([item[@"readOnly"] boolValue] || [type isEqualToString:@"unsupported"]) return;
    NSString *name = Text(item[@"name"]);
    NSString *current = Text(item[@"value"]);
    if ([type isEqualToString:@"boolean"]) {
        [self setValue:[current boolValue] ? @"false" : @"true" forItem:item];
        return;
    }
    UIAlertController *edit = [UIAlertController alertControllerWithTitle:name message:Text(item[@"description"])
        preferredStyle:UIAlertControllerStyleAlert];
    if ([type isEqualToString:@"enum"] && [item[@"options"] isKindOfClass:NSArray.class]) {
        for (id option in item[@"options"]) {
            NSString *value = [option isKindOfClass:NSDictionary.class] ? Text(option[@"value"]) : Text(option);
            NSString *label = [option isKindOfClass:NSDictionary.class] ? Text(option[@"label"]) : value;
            if (value.length) [edit addAction:[UIAlertAction actionWithTitle:label.length ? label : value
                style:UIAlertActionStyleDefault handler:^(__unused UIAlertAction *action) {
                [self setValue:value forItem:item];
            }]];
        }
    } else {
        [edit addTextFieldWithConfigurationHandler:^(UITextField *field) {
            field.text = current;
            field.autocorrectionType = UITextAutocorrectionTypeNo;
            field.autocapitalizationType = UITextAutocapitalizationTypeNone;
        }];
        __weak UIAlertController *weakEdit = edit;
        [edit addAction:[UIAlertAction actionWithTitle:@"Apply" style:UIAlertActionStyleDefault handler:^(__unused UIAlertAction *action) {
            [self setValue:weakEdit.textFields.firstObject.text ?: @"" forItem:item];
        }]];
    }
    [edit addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:edit animated:YES completion:nil];
}

@end

@implementation NSCDesktopPanelController {
    NSCGameRuntime *_runtime;
    NSDictionary *_panel;
    UIImageView *_image;
    UIScrollView *_scroll;
    UILabel *_state;
    NSString *_session, *_viewer;
    NSTimer *_refresh;
    NSLayoutConstraint *_imageWidth, *_imageHeight;
    NSInteger _width, _height;
    long long _frame;
    NSUInteger _generation;
    BOOL _busy, _active;
    UITapGestureRecognizer *_tap, *_doubleTap;
    UIPanGestureRecognizer *_wheel;
    UISwitch *_internalScroll;
}

- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime panel:(NSDictionary *)panel {
    if ((self = [super init])) {
        _runtime = runtime; _panel = [panel copy]; _viewer = NSUUID.UUID.UUIDString;
        _session = @"";
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = Text(_panel[@"name"]);
    self.navigationItem.largeTitleDisplayMode = UINavigationItemLargeTitleDisplayModeNever;
    self.view.backgroundColor = UIColor.systemBackgroundColor;
    self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc] initWithBarButtonSystemItem:UIBarButtonSystemItemRefresh
        target:self action:@selector(refresh)];
    UIStackView *root = Vertical();
    root.translatesAutoresizingMaskIntoConstraints = NO;
    [self.view addSubview:root];
    UILayoutGuide *safe = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [root.topAnchor constraintEqualToAnchor:safe.topAnchor constant:8],
        [root.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor constant:-8],
        [root.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:14],
        [root.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-14]
    ]];
    _state = [UILabel new];
    CompactLabel(_state, 12, UIFontWeightRegular);
    _state.text = @"Opening desktop controls";
    [root addArrangedSubview:_state];
    UIStackView *scrollMode = [UIStackView new];
    scrollMode.axis = UILayoutConstraintAxisHorizontal;
    scrollMode.alignment = UIStackViewAlignmentCenter;
    scrollMode.spacing = 8;
    UILabel *modeLabel = [UILabel new];
    CompactLabel(modeLabel, 12, UIFontWeightRegular);
    modeLabel.text = @"Scroll within panel";
    _internalScroll = [UISwitch new];
    [_internalScroll addTarget:self action:@selector(setScrollMode) forControlEvents:UIControlEventValueChanged];
    [scrollMode addArrangedSubview:modeLabel];
    [scrollMode addArrangedSubview:_internalScroll];
    [scrollMode addArrangedSubview:[UIView new]];
    [root addArrangedSubview:scrollMode];
    _scroll = [UIScrollView new];
    _scroll.backgroundColor = UIColor.secondarySystemBackgroundColor;
    [root addArrangedSubview:_scroll];
    UIView *content = [UIView new];
    content.translatesAutoresizingMaskIntoConstraints = NO;
    [_scroll addSubview:content];
    _image = [UIImageView new];
    _image.translatesAutoresizingMaskIntoConstraints = NO;
    _image.contentMode = UIViewContentModeScaleToFill;
    _image.userInteractionEnabled = YES;
    [content addSubview:_image];
    _imageWidth = [_image.widthAnchor constraintEqualToConstant:220];
    _imageHeight = [_image.heightAnchor constraintEqualToConstant:1];
    [NSLayoutConstraint activateConstraints:@[
        [content.leadingAnchor constraintEqualToAnchor:_scroll.contentLayoutGuide.leadingAnchor],
        [content.trailingAnchor constraintEqualToAnchor:_scroll.contentLayoutGuide.trailingAnchor],
        [content.topAnchor constraintEqualToAnchor:_scroll.contentLayoutGuide.topAnchor],
        [content.bottomAnchor constraintEqualToAnchor:_scroll.contentLayoutGuide.bottomAnchor],
        [content.widthAnchor constraintEqualToAnchor:_scroll.frameLayoutGuide.widthAnchor],
        [_image.topAnchor constraintEqualToAnchor:content.topAnchor constant:4],
        [_image.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-4],
        [_image.centerXAnchor constraintEqualToAnchor:content.centerXAnchor],
        _imageWidth, _imageHeight
    ]];
    _tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(tap:)];
    _doubleTap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(tap:)];
    _doubleTap.numberOfTapsRequired = 2;
    [_tap requireGestureRecognizerToFail:_doubleTap];
    _wheel = [[UIPanGestureRecognizer alloc] initWithTarget:self action:@selector(wheel:)];
    [_image addGestureRecognizer:_tap];
    [_image addGestureRecognizer:_doubleTap];
    [_image addGestureRecognizer:_wheel];
    [self setScrollMode];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self adjustPanelSize];
}

- (void)adjustPanelSize {
    if (!_width || !_height) return;
    CGFloat target = MIN(MAX(1, CGRectGetWidth(_scroll.bounds) - 8), MIN(385, _width * 0.85));
    _imageWidth.constant = target;
    _imageHeight.constant = target * _height / _width;
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    if (_active) return;
    _active = YES;
    _busy = YES;
    const NSUInteger token = ++_generation;
    [_runtime plugin:@{@"op": @"panelOpen", @"panel": Text(_panel[@"id"]), @"viewer": _viewer}
         completion:^(NSDictionary *reply) {
        if (token != self->_generation || !self->_active) {
            NSString *orphan = Text(reply[@"session"]);
            if (orphan.length) [self close:orphan];
            return;
        }
        self->_busy = NO;
        self->_session = Text(reply[@"session"]);
        [self receive:reply];
        if (self->_session.length)
            self->_refresh = [NSTimer scheduledTimerWithTimeInterval:1.1 target:self selector:@selector(refresh)
                userInfo:nil repeats:YES];
    }];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    if (self.isMovingFromParentViewController || self.navigationController.isBeingDismissed || self.isBeingDismissed) {
        _active = NO;
        _generation++;
        [_refresh invalidate];
        _refresh = nil;
        [self close:_session];
        _session = @"";
    }
}

- (void)close:(NSString *)session {
    if (!session.length) return;
    [_runtime plugin:@{@"op": @"panelClose", @"session": session, @"viewer": _viewer}
          completion:^(__unused NSDictionary *reply) {}];
}

- (void)setScrollMode {
    _wheel.enabled = _internalScroll.isOn;
    _scroll.scrollEnabled = !_internalScroll.isOn;
}

- (void)refresh {
    if (!_active || _busy || !_session.length || self.presentedViewController) return;
    [self request:@{@"op": @"panelFrame"}];
}

- (void)request:(NSDictionary *)action {
    if (_busy || !_active || !_session.length) return;
    _busy = YES;
    NSMutableDictionary *request = [action mutableCopy];
    request[@"session"] = _session;
    request[@"frame"] = @(_frame);
    const NSUInteger token = _generation;
    [_runtime plugin:request completion:^(NSDictionary *reply) {
        if (!self->_active || token != self->_generation) return;
        self->_busy = NO;
        [self receive:reply];
    }];
}

- (void)receive:(NSDictionary *)reply {
    if (![reply[@"ok"] boolValue]) { _state.text = Text(reply[@"error"]); return; }
    NSString *png = Text(reply[@"png"]);
    NSInteger width = [reply[@"width"] integerValue], height = [reply[@"height"] integerValue];
    if (png.length && png.length <= 4 * 1024 * 1024 && width > 0 && width <= 512 && height > 0 && height <= 4096) {
        NSData *data = [[NSData alloc] initWithBase64EncodedString:png options:0];
        UIImage *frame = nil;
        CGImageSourceRef source = data ? CGImageSourceCreateWithData((__bridge CFDataRef)data, NULL) : NULL;
        if (source) {
            NSDictionary *properties = CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0, NULL));
            if ([properties[(__bridge NSString *)kCGImagePropertyPixelWidth] integerValue] == width &&
                [properties[(__bridge NSString *)kCGImagePropertyPixelHeight] integerValue] == height) {
                CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, NULL);
                if (image) {
                    frame = [UIImage imageWithCGImage:image];
                    CGImageRelease(image);
                }
            }
            CFRelease(source);
        }
        if (frame && frame.size.width == width && frame.size.height == height) {
            _image.image = frame;
            _width = width;
            _height = height;
            _frame = [reply[@"frame"] longLongValue];
            [self adjustPanelSize];
        }
    }
    NSString *warning = Text(reply[@"warning"]);
    _state.text = warning.length ? warning : @"Tap a control to interact";
    if ([reply[@"focus"] isKindOfClass:NSDictionary.class] && !self.presentedViewController)
        [self edit:reply[@"focus"]];
}

- (NSDictionary *)pointFor:(UIGestureRecognizer *)gesture {
    if (!_image.image || !_width || !_height) return nil;
    CGPoint location = [gesture locationInView:_image];
    CGSize bounds = _image.bounds.size;
    if (bounds.width <= 0 || bounds.height <= 0 || !CGRectContainsPoint(_image.bounds, location)) return nil;
    NSInteger x = MIN(_width - 1, (NSInteger)(location.x * _width / bounds.width));
    NSInteger y = MIN(_height - 1, (NSInteger)(location.y * _height / bounds.height));
    return @{@"x": @(x), @"y": @(y)};
}

- (void)tap:(UITapGestureRecognizer *)gesture {
    NSDictionary *point = [self pointFor:gesture];
    if (point) [self request:@{@"op": @"panelTouch", @"action": @"tap", @"x": point[@"x"],
                               @"y": point[@"y"], @"clicks": @(gesture.numberOfTapsRequired)}];
}

- (void)wheel:(UIPanGestureRecognizer *)gesture {
    if (gesture.state != UIGestureRecognizerStateEnded) return;
    NSDictionary *point = [self pointFor:gesture];
    if (!point) return;
    CGPoint movement = [gesture translationInView:_image];
    NSInteger steps = MAX(-20, MIN(20, (NSInteger)round(-movement.y / 35)));
    if (steps) [self request:@{@"op": @"panelTouch", @"action": @"wheel",
                              @"x": point[@"x"], @"y": point[@"y"], @"rotation": @(steps)}];
}

- (void)edit:(NSDictionary *)focus {
    NSString *type = Text(focus[@"type"]);
    NSString *token = Text(focus[@"token"]);
    if (!token.length) return;
    UIAlertController *dialog = [UIAlertController alertControllerWithTitle:@"Desktop control"
        message:nil preferredStyle:UIAlertControllerStyleAlert];
    if ([type isEqualToString:@"choice"] && [focus[@"options"] isKindOfClass:NSArray.class]) {
        NSInteger index = 0;
        for (id label in focus[@"options"]) {
            const NSInteger optionIndex = index++;
            [dialog addAction:[UIAlertAction actionWithTitle:Text(label) style:UIAlertActionStyleDefault
                handler:^(__unused UIAlertAction *action) {
                [self request:@{@"op": @"panelChoice", @"focus": token, @"index": @(optionIndex)}];
            }]];
        }
    } else if ([type isEqualToString:@"text"] || [type isEqualToString:@"password"]) {
        BOOL readOnly = [focus[@"readOnly"] boolValue], secret = [type isEqualToString:@"password"];
        [dialog addTextFieldWithConfigurationHandler:^(UITextField *field) {
            field.enabled = !readOnly;
            field.secureTextEntry = secret;
            field.text = secret ? @"" : Text(focus[@"value"]);
            field.autocorrectionType = UITextAutocorrectionTypeNo;
        }];
        if (!readOnly) {
            __weak UIAlertController *weakDialog = dialog;
            [dialog addAction:[UIAlertAction actionWithTitle:@"Apply" style:UIAlertActionStyleDefault
                handler:^(__unused UIAlertAction *action) {
                NSString *text = weakDialog.textFields.firstObject.text ?: @"";
                if (text.length <= 8192) [self request:@{@"op": @"panelText", @"focus": token,
                    @"text": text, @"submit": @(![focus[@"multiline"] boolValue])}];
                weakDialog.textFields.firstObject.text = @"";
            }]];
        }
    } else { return; }
    [dialog addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:dialog animated:YES completion:nil];
}

@end
