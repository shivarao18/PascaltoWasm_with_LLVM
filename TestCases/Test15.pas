program TestClassScope;

type
  MyClass = class;
  public
    X: Integer;
  end;

var
  Obj1, Obj2: MyClass;

begin
  Obj1 := MyClass.Create;
  Obj2 := MyClass.Create;

  Obj1.X := 10;
  Obj2.X := 99;

  WRITELN(Obj1.X);
  WRITELN(Obj2.X);
end.