#pragma once
#include <atomic>
struct LocalRequest { std::atomic<bool> cancelled{false}; };
