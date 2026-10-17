// Consumer regression: check the actual generated promise callback, using real
// Hyprutils pointers when the source checker is given its reviewed include path.
#include <cassert>
#include <csignal>
#include <functional>
#include <memory>
#include <string>
#include <sys/types.h>
#ifdef OMAMOBILE_TEST_HYPRUTILS
#include <hyprutils/memory/WeakPtr.hpp>
template<class T> using SP = Hyprutils::Memory::CSharedPointer<T>;
template<class T> using WP = Hyprutils::Memory::CWeakPointer<T>;
#else
template<class T> using SP = std::shared_ptr<T>;
template<class T> using WP = std::weak_ptr<T>;
#endif

#define LOG(...) ((void)0)
static int terminateRequests = 0;
static int recordKill(pid_t pid, int signal) { assert(pid == 123 && signal == SIGKILL); terminateRequests++; return 0; }
#define kill recordKill
struct CAsyncDialogBox {};
template<class T> struct CPromiseResult {
    T value;
    bool error = false;
    bool hasError() const { return error; }
    T result() const { return value; }
};
static int destroyedActors = 0;
struct Actor {
    bool dialogSaidWait = false;
    SP<CAsyncDialogBox> dialogBox;
    ~Actor() { destroyedActors++; }
    auto response([[maybe_unused]] const WP<Actor>& owner) {
        const pid_t dialogWmPID = 123;
        const std::string OPTION_TERMINATE_STR = "Terminate", OPTION_WAIT_STR = "Wait";
        return
#include "anr-dialog-callback.inc"
        ;
    }
};
#undef kill
template<class T> static SP<T> make() { return SP<T>(new T); }
static SP<CPromiseResult<std::string>> result(const std::string& value, bool error = false) {
    auto response = make<CPromiseResult<std::string>>(); response->value = value; response->error = error; return response;
}

int main() {
    auto actor = make<Actor>(); actor->dialogBox = make<CAsyncDialogBox>();
    auto callback = actor->response(WP<Actor>(actor));
    callback(result("Wait\n")); assert(actor->dialogSaidWait); assert(terminateRequests == 0);
    actor->dialogSaidWait = false;
    callback(result("Terminate\n")); assert(terminateRequests == 1);
    callback(result("", true)); assert(!actor->dialogSaidWait); assert(terminateRequests == 1);

    // A window can outlive its ANR actor. The callback itself must not retain it.
    auto survivingWindow = make<int>(); (void)survivingWindow;
    WP<Actor> expired(actor); const int before = destroyedActors;
    actor.reset(); assert(expired.expired()); assert(destroyedActors == before + 1);
    callback(result("Wait\n")); callback(result("Terminate\n")); assert(terminateRequests == 1);

    actor = make<Actor>(); auto oldDialog = make<CAsyncDialogBox>(); actor->dialogBox = oldDialog;
    auto old = actor->response(WP<Actor>(actor));
    actor->dialogBox.reset(); // Closed, but another local owner still holds the actor/dialog.
    old(result("Wait\n")); old(result("Terminate\n")); assert(!actor->dialogSaidWait); assert(terminateRequests == 1);
    actor->dialogBox = make<CAsyncDialogBox>(); // A new dialog for the same live client.
    auto current = actor->response(WP<Actor>(actor));
    old(result("Wait\n")); old(result("Terminate\n")); assert(!actor->dialogSaidWait); assert(terminateRequests == 1);
    current(result("Wait\n")); assert(actor->dialogSaidWait);
    WP<CAsyncDialogBox> weakOld(oldDialog); oldDialog.reset(); assert(weakOld.expired());
    WP<Actor> weakActor(actor); actor.reset(); assert(weakActor.expired());
    current(result("Wait\n")); current(result("Terminate\n")); assert(terminateRequests == 1);
}
