(module
  (type (func (param i32) (result i32)))
  (import "env" "table" (table 1 funcref))
  (func (type 0) (local i32)
    local.get 0
    i32.const 0
    call_indirect (type 0)
    local.set 1
    block (result i32)
      local.get 1
      local.get 1
      i32.const 0
      i32.ge_s
      br_if 0
      i32.const -1
      i32.mul
    end
  )
  (; (export "abs ○ f" (func 0)) ;)
  (export "abs" (func 0))
  (export "main" (func 0))
  )
