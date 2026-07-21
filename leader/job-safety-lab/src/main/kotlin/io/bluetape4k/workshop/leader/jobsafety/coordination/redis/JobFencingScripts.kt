package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.redis.lettuce.script.RedisScript

internal object JobFencingScripts {
    val acquire =
        RedisScript(
            """
            local epoch = redis.call('GET', KEYS[3])
            if not epoch then
              redis.call('SET', KEYS[3], ARGV[3], 'NX')
              epoch = redis.call('GET', KEYS[3])
            end
            if epoch ~= ARGV[3] then return 'E' end

            local lease = redis.call('GET', KEYS[1])
            if lease then
              local separator = string.find(lease, '|', 1, true)
              if not separator then return 'X' end
              local owner = string.sub(lease, 1, separator - 1)
              local fence = tonumber(string.sub(lease, separator + 1))
              if not fence then return 'X' end
              local counter = tonumber(redis.call('GET', KEYS[2]))
              if not counter or counter < fence then return 'H' end
              if owner ~= ARGV[1] then return 'C' end
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 'O|' .. fence
            end

            local fence = redis.call('INCR', KEYS[2])
            redis.call('SET', KEYS[1], ARGV[1] .. '|' .. fence, 'PX', ARGV[2])
            return 'A|' .. fence
            """.trimIndent(),
        )

    val renew =
        RedisScript(
            """
            if redis.call('GET', KEYS[2]) ~= ARGV[4] then return -1 end
            local expected = ARGV[1] .. '|' .. ARGV[2]
            if redis.call('GET', KEYS[1]) ~= expected then return 0 end
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """.trimIndent(),
        )

    val release =
        RedisScript(
            """
            if redis.call('GET', KEYS[2]) ~= ARGV[3] then return -1 end
            local expected = ARGV[1] .. '|' .. ARGV[2]
            if redis.call('GET', KEYS[1]) ~= expected then return 0 end
            redis.call('DEL', KEYS[1])
            return 1
            """.trimIndent(),
        )
}
