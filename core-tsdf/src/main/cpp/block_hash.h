#pragma once
#include <cstdint>

struct BlockKey {
    int32_t x, y, z;
    bool operator==(const BlockKey& o) const noexcept {
        return x == o.x && y == o.y && z == o.z;
    }
};

struct BlockKeyHash {
    size_t operator()(const BlockKey& k) const noexcept {
        return (static_cast<uint32_t>(k.x) * 73856093u)
             ^ (static_cast<uint32_t>(k.y) * 19349663u)
             ^ (static_cast<uint32_t>(k.z) * 83492791u);
    }
};
