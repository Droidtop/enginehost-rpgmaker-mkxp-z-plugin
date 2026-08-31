# Ruby 1.9's build tools predate the removal of the legacy TRUE/FALSE
# constants. Keep this compatibility confined to the host-side generator;
# neither constant is added to the target runtime.
TRUE = true unless Object.const_defined?(:TRUE)
FALSE = false unless Object.const_defined?(:FALSE)
