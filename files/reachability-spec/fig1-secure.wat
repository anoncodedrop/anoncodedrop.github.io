(module
  (type (func (param i32) (result i32)))
  (import "env" "table" (table 1 funcref))
  (func $abs (type 0) (local i32)
    local.get 0
    i32.const 0
    call_indirect (type 0)
    local.set 1
    block (result i32)
      block
        local.get 1
        i32.const 0x8000_0000
        i32.ne
        br_if 0
        unreachable
      end
      local.get 1
      local.get 1
      i32.const 0
      i32.ge_s
      br_if 0
      i32.const -1
      i32.mul
    end
  )
  (export "abs" (func $abs))
  (export "main" (func $abs))
)
