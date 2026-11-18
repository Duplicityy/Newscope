#import <UIKit/UIKit.h>
#import "GameRuntime.h"

@interface NSCPluginListController : UITableViewController
- (instancetype)initWithRuntime:(NSCGameRuntime *)runtime desktop:(BOOL)desktop;
@end
